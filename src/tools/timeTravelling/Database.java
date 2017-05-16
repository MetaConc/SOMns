package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;
import static som.vm.constants.Nil.valueIsNil;

import java.util.HashMap;
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

  private enum SObjectTypes {
    SMutable,
    SIMMutable,
    SPromise,
    SResolver;

    int id() {
      return this.ordinal();
    }
  }

  private class SRawValue {
    public ConcreteValueType type;
    public Object value;

    SRawValue(final ConcreteValueType type, final Object value){
      this.type = type;
      this.value = value;
    }
  }

  private enum ConcreteValueType {
    primitive,
    ObjectWithSlots,
    Nil;
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

  public void createActor(final Transaction transaction, final Actor actor) {
    transaction.run(matchSession + " CREATE (a:Actor {actorId: {actorId}}) - [:BELONGS_TO] -> (session)",
        parameters("actorId", actor.getId(), "sessionId", sessionId));
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
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = transaction.run(matchActor + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}}) - [:TURN] -> (actor)"
        + " CREATE (target:Object) -[:TARGETOF]->(turn) "
        + " return ID(target), ID(turn)"
        , parameters("actorId", actorId, "messageId", messageId, "messageName", msg.getSelector().getString(), "sessionId", sessionId));

    Record record = result.single();

    // write all slots to db
    long targetId = record.get("ID(target)").asLong();
    HashMap<SlotDefinition, StorageLocation> locations = target.getObjectLayout().getStorageLocations();
    for (Entry<SlotDefinition, StorageLocation> entry : locations.entrySet()) {
      writeSlot(transaction, targetId, entry.getKey(), entry.getValue().read(target));
    }

    // write all arguments to db
    long argumentId = record.get("ID(turn)").asLong();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  // unbox the abstract somvalue and store the object needed to id the object in result, return the type of boxed value
  private SRawValue getConcreteValue(final Object value) {
    if (value instanceof SFarReference) {
      return getConcreteValue(((SFarReference) value).getValue());
    } else if (value instanceof SPromise) {
      return new SRawValue(ConcreteValueType.primitive, ((SPromise) value).getPromiseId());
    } else if (value instanceof SResolver) {
      return new SRawValue(ConcreteValueType.primitive, ((SResolver) value).getPromise().getPromiseId());
    } else if (value instanceof SAbstractObject) {
      if(value instanceof SMutableObject){
        return new SRawValue(ConcreteValueType.ObjectWithSlots, null);
      }  else if (valueIsNil(value)) { // is it useful to store null values? can't we use closed world assumption?
        return new SRawValue(ConcreteValueType.Nil, null);
      } else {
        throw new RuntimeException("unexpected Sabstract type: " + value.getClass());
      }
    } else if (value instanceof Long||value instanceof Double||value instanceof Boolean||value instanceof String) {
      return new SRawValue(ConcreteValueType.primitive, value);
    } else {
      throw new RuntimeException("unexpected argument type");
    }
  }

  private void writeSlot(final Transaction transaction, final long parentId, final SlotDefinition slotDef, final Object slotValue) {
    SRawValue value = getConcreteValue(slotValue);
    switch(value.type){
      case primitive:
        transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (slot {slotId: {slotId}, slotName: {slotName}, value: {slotValue}}) - [:SLOT] -> (parent)",
            parameters("parentId", parentId, "slotId", slotDef.getName().getSymbolId(), "slotName", slotDef.getName().getString(), "slotValue", value.value));
        break;
      case Nil:
        break;
      case ObjectWithSlots:
        writeSObjectAsSlot(transaction, parentId, slotDef, (SMutableObject) slotValue);
        break;
    }
  }

  // Sobject is a slot of an object, store it db
  private void writeSObjectAsSlot(final Transaction transaction, final Long parentId, final SlotDefinition slotDef, final SMutableObject slotValue){
    StatementResult result = transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (slot {slotId: {slotId}, slotName: {slotName}, type: {SObjectType}}) - [:SLOT] -> (parent) return ID(slot)"
        , parameters("parentId", parentId, "slotId", slotValue.getSOMClass().getName().getSymbolId(), "slotName", slotValue.getSOMClass().getName().getString(), "SObjectType", SObjectTypes.SMutable.id()));
    long slotId = getIdFromStatementResult(result,"slot");
    for (Entry<SlotDefinition, StorageLocation> entry : slotValue.getObjectLayout().getStorageLocations().entrySet()) {
      writeSlot(transaction, slotId, entry.getKey(), entry.getValue().read(slotValue));
    }
  }

  private void writeArgument(final Transaction transaction, final long parentId, final int argCount, final Object argValue) {
    SRawValue value = getConcreteValue(argValue);
    switch(value.type){
      case primitive:
        transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (argument {argIdx: {argIdx}, value: {argValue}}) - [:ARGUMENT] -> (parent)",
            parameters("argIdx", argCount, "argValue", value.value, "parentId", parentId));
        break;
      case Nil:
        break;
      case ObjectWithSlots:
        writeSObjectAsArgument(transaction, parentId, argCount, (SMutableObject) argValue);
        break;
    }
  }

  // Sobject is passed as argument, store it in db
  private void writeSObjectAsArgument(final Transaction transaction, final Long parentId, final int argCount, final SMutableObject argValue){
    StatementResult result = transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (argument {argIdx: {argIdx}, type: {SObjectType}}) - [:ARGUMENT] -> (parent) return ID(argument)",
        parameters("argIdx", argCount, "parentId", parentId, "SObjectType", SObjectTypes.SMutable.id()));
    long argumentId = getIdFromStatementResult(result,"argument");
    for (Entry<SlotDefinition, StorageLocation> entry : argValue.getObjectLayout().getStorageLocations().entrySet()) {
      writeSlot(transaction, argumentId, entry.getKey(), entry.getValue().read(argValue));
    }
  }

  private long getIdFromStatementResult(final StatementResult result, final String name){
    return result.single().get("ID(" + name+")").asLong();
  }

  private String matchSession = "MATCH (session) where ID(session) = {sessionId}";
  private String matchActor = matchSession + "MATCH (actor: Actor {actorId: {actorId}}) - [:BELONGS_TO] -> (session)";
}
