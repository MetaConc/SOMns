package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;
import static som.vm.constants.Nil.valueIsNil;

import java.util.Map.Entry;

import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;

import som.VM;
import som.compiler.MixinDefinition.SlotDefinition;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.SFarReference;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.objectstorage.StorageLocation;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SClass;
import som.vmobjects.SObject;
import som.vmobjects.SObject.SImmutableObject;
import som.vmobjects.SObject.SMutableObject;
import tools.concurrency.ActorExecutionTrace;

public final class Database {
  private static Database singleton;
  private Driver driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "timetraveling"));
  private static long sessionId;

  private Database() {
    Session session = startSession();
    Transaction transaction = startTransaction(session);
    StatementResult result = transaction.run("CREATE (session: SessionId) return ID(session)");
    sessionId = getIdFromStatementResult(result,"session");
    String[] args = VM.getArguments();
    for(int argCount = 0; argCount<args.length; argCount++){
      writeArgument(transaction, sessionId, argCount, args[argCount]);
    }
    commitTransaction(transaction);
    endSession(session);
    ActorExecutionTrace.SessionCreated(sessionId);
  }

  // singleton design pattern
  public static Database getDatabaseInstance() {
    if (singleton == null) {
      singleton = new Database();
    }
    return singleton;
  }

  public Session startSession() {
    return driver.session();
  }

  public Transaction startTransaction(final Session session) {
    return session.beginTransaction();
  }

  public void commitTransaction(final Transaction transaction) {
    transaction.success();
  }

  public void endSession(final Session session) {
    session.close();
  }

  private class ValueAndType {
    public ConcreteValueType type;
    public Object value;

    ValueAndType(final ConcreteValueType type, final Object value){
      this.type = type;
      this.value = value;
    }
  }

  private enum ConcreteValueType {
    primitive,
    ObjectWithSlots,
    Nil;
  }

  public enum databaseState {
    not_stored,
    valid,
    outdated;
  }


  public void createActor(final Transaction transaction, final Actor actor) {
    transaction.run(matchSession + " CREATE (actor:Actor {actorId: {actorId}}) - [:BELONGS_TO] -> (session)",
        parameters("actorId", actor.getId(), "sessionId", sessionId));
    actor.inDatabase=true;
  }

  public void createConstructor(final Transaction transaction, final Long messageId, final EventualMessage msg, final long actorId, final SClass target) {
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = transaction.run(matchActor + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}}) - [:TURN]-> (actor)"
        + " return ID(turn)"
        , parameters("actorId", actorId, "messageId", messageId, "messageName", msg.getSelector().getString(), "sessionId", sessionId));

    Record record = result.single();
    long argumentId = record.get("ID(turn)").asLong();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  // the arguments of the message are already stored in the log.
  public void createCheckpoint(final Transaction transaction, final Long messageId, final EventualMessage msg, final Long actorId, final SMutableObject target) {
    final databaseState state = findOrCreateSObject(transaction, target);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    String query = matchSObject;
    if(state == databaseState.not_stored){
      query = query + " " + matchActor + " CREATE (SObject) - [:ROOT_OBJECT] -> (actor)";
    }
    query = query + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}}) - [:TARGET] -> (SObject)";

    StatementResult result = transaction.run(query + " return ID(turn)"
        , parameters("sessionId", sessionId, "actorId", actorId, "SObjectId", target.getRef(), "messageId", messageId, "messageName", msg.getSelector().getString()));

    // write all arguments to db
    long argumentId = getIdFromStatementResult(result, "turn");
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  // unbox the abstract somvalue and store the object needed to id the object in result, return the type of boxed value
  private ValueAndType getValueAndType(final Object value) {
    if (value instanceof SFarReference) {
      return getValueAndType(((SFarReference) value).getValue());
    } else if (value instanceof SPromise) {
      return new ValueAndType(ConcreteValueType.primitive, ((SPromise) value).getPromiseId());
    } else if (value instanceof SResolver) {
      return new ValueAndType(ConcreteValueType.primitive, ((SResolver) value).getPromise().getPromiseId());
    } else if (value instanceof SAbstractObject) {
      if(value instanceof SMutableObject || value instanceof SImmutableObject){
        return new ValueAndType(ConcreteValueType.ObjectWithSlots, value);
      }  else if (valueIsNil(value)) { // is it useful to store null values? can't we use closed world assumption?
        return new ValueAndType(ConcreteValueType.Nil, null);
      } else {
        throw new RuntimeException("unexpected Sabstract type: " + value.getClass());
      }
    } else if (value instanceof Long||value instanceof Double||value instanceof Boolean||value instanceof String) {
      return new ValueAndType(ConcreteValueType.primitive, value);
    } else {
      throw new RuntimeException("unexpected argument type");
    }
  }

  private databaseState findOrCreateSObject(final Transaction transaction, final SObject object) {
    StatementResult result;
    long ref;

    databaseState oldState = object.dbState;

    switch(oldState){
      case not_stored:
        result = transaction.run("CREATE (SObject: SObject {mixinId: {mixinId}})"
            + " return ID(SObject)",
            parameters("sessionId", sessionId, "mixinId", object.getSOMClass().getMixinDefinition().getMixinId().toString()));
        ref = getIdFromStatementResult(result, "SObject");
        object.setRef(ref);
        for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
          writeSlot(transaction, ref, entry.getKey(), entry.getValue().read(object));
        }
        break;
      case outdated:
        result = transaction.run(matchSObject + " CREATE (update: SObject) - [:UPDATE] -> (SObject)"
            + " return ID(update)",
            parameters("SObjectId", object.getRef()));
        ref = getIdFromStatementResult(result, "update");
        object.setRef(ref);
        for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
          writeSlot(transaction, ref, entry.getKey(), entry.getValue().read(object));
        }
        break;
      case valid:
        break;
    }
    return oldState;
  }

  private void writeSlot(final Transaction transaction, final long parentId, final SlotDefinition slotDef, final Object slotValue) {
    ValueAndType value = getValueAndType(slotValue);
    switch(value.type){
      case primitive:
        transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (slot {slotId: {slotId}, slotName: {slotName}, value: {slotValue}}) - [:SLOT] -> (parent)",
            parameters("parentId", parentId, "slotId", slotDef.getName().getSymbolId(), "slotName", slotDef.getName().getString(), "slotValue", value.value));
        break;
      case Nil:
        break;
      case ObjectWithSlots:
        findOrCreateSObject(transaction, (SObject) value.value);
        transaction.run(matchSObject + " MATCH (parent) where ID(parent)={parentId} CREATE (SObject) - [:SLOT] ->  (parent)",
            parameters("parentId", parentId, "SObjectId", ((SObject) value.value).getRef()));
        break;
    }
  }

  private void writeArgument(final Transaction transaction, final long parentId, final int argCount, final Object argValue) {
    ValueAndType value = getValueAndType(argValue);
    switch(value.type){
      case primitive:
        transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (argument {argIdx: {argIdx}, value: {argValue}}) - [:ARGUMENT] -> (parent)",
            parameters("argIdx", argCount, "argValue", value.value, "parentId", parentId));
        break;
      case Nil:
        break;
      case ObjectWithSlots:
        findOrCreateSObject(transaction, (SObject) value.value);
        transaction.run(matchSObject + " MATCH (parent) where ID(parent)={parentId} CREATE (SObject) - [:ARGUMENT] ->  (parent) SET (SOBject {argIdx: {argIdx}}",
            parameters("parentId", parentId, "SObjectId", ((SObject) value.value).getRef(), "argIdx", argCount));

        break;
    }
  }

  private long getIdFromStatementResult(final StatementResult result, final String name){
    return result.single().get("ID(" + name+")").asLong();
  }

  private String matchSession = "MATCH (session: SessionId) where ID(session) = {sessionId}";
  private String matchActor = matchSession + " MATCH (actor: Actor {actorId: {actorId}}) - [:BELONGS_TO] -> (session)";
  private String matchSObject = "MATCH (SObject: SObject) where ID(SObject) = {SObjectId}";
}