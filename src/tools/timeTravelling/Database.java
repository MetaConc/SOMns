package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;
import static som.vm.constants.Nil.valueIsNil;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.Value;

import som.VM;
import som.compiler.MixinDefinition.SlotDefinition;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.SFarReference;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.objectstorage.StorageLocation;
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
  public static synchronized Database getDatabaseInstance() {
    if (singleton == null) {
      singleton = new Database();
    }
    return singleton;
  }

  public Session startSession() {
    return driver.session();
  }

  public void endSession(final Session session) {
    session.close();
  }

  public Transaction startTransaction(final Session session) {
    return session.beginTransaction();
  }

  public void commitTransaction(final Transaction transaction) {
    transaction.success();
  }

  private enum SomValueTypes {
    SFarReference,
    SPromise,
    SResolver,
    SAbstractObject,
    SMutableObject,
    SImmutableObject,
    Nil,
    Long,
    Double,
    Boolean,
    String;
  }

  public enum databaseState {
    not_stored,
    valid,
    outdated;
  }

  private long getIdFromStatementResult(final StatementResult result, final String name){
    return result.single().get("ID(" + name+")").asLong();
  }

  private String matchSession = "MATCH (session: SessionId) where ID(session) = {sessionId}";
  private String matchActor = matchSession + " MATCH (actor: Actor {actorId: {actorId}}) - [:BELONGS_TO] -> (session)";
  private String matchSObject = "MATCH (SObject: SObject) where ID(SObject) = {SObjectId}";


  /* --------------------------------------- */
  /* -                 Writing             - */
  /* --------------------------------------- */

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
    final long ref = createSObject(transaction, target);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    String query = matchSObject + " " + matchActor + " CREATE (SObject) - [:IN] -> (actor)";
    query = query + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}}) - [:TARGET] -> (SObject)";

    StatementResult result = transaction.run(query + " return ID(turn)"
        , parameters("sessionId", sessionId, "actorId", actorId, "SObjectId", ref, "messageId", messageId, "messageName", msg.getSelector().getString()));

    // write all arguments to db
    long argumentId = getIdFromStatementResult(result, "turn");
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  private long createSObject(final Transaction transaction, final SObject object){
    StatementResult result = transaction.run("CREATE (SObject: SObject {className: {className}})"
        + " return ID(SObject)",
        parameters("sessionId", sessionId, "className", object.getSOMClass().getName().getString()));
    Long ref = getIdFromStatementResult(result, "SObject");
    for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
      writeSlot(transaction, ref, entry.getKey(), entry.getValue().read(object));
    }
    return ref;
  }

  private void writeSlot(final Transaction transaction, final long parentId, final SlotDefinition slotDef, final Object slotValue) {
    Long ref = writeValue(transaction, slotValue);
    if(ref != null){
      transaction.run(
          "MATCH (parent) where ID(parent)={parentId}"
          + " MATCH (slot) where ID(slot) = {slotRef}"
          + "CREATE (slot) - [:SLOT {slotId: {slotId}}] -> (parent)",
          parameters("parentId", parentId, "slotRef", ref, "slotId", slotDef.getName().getString()));
    }
  }

  private void writeArgument(final Transaction transaction, final long parentId, final int argIdx, final Object argValue) {
    Long ref = writeValue(transaction, argValue);
    if(ref != null){
      transaction.run(
          "MATCH (parent) where ID(parent)={parentId}"
              + " MATCH (argument) where ID(argument) = {argRef}"
              + "CREATE (argument) - [:ARGUMENT {argIdx: {argIdx}}] -> (parent)",
              parameters("parentId", parentId, "argRef", ref, "argIdx", argIdx));
    }
  }

  private Long writeValue(final Transaction transaction, final Object value) {
    StatementResult result;
    if (value instanceof SFarReference) {
      throw new RuntimeException("not yet implemented");
    } else if (value instanceof SPromise) {
      throw new RuntimeException("not yet implemented");
    } else if (value instanceof SResolver) {
      throw new RuntimeException("not yet implemented");
    } else if (value instanceof SMutableObject || value instanceof SImmutableObject){
      return createSObject(transaction, (SObject) value);
    } else if (valueIsNil(value)) { // is it useful to store null values? can't we use closed world assumption?
      return null;
    } else if (value instanceof Long){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueTypes.Long.name()));
    } else if (value instanceof Double){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueTypes.Double.name()));
    } else if (value instanceof Boolean){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueTypes.Boolean.name()));
    } else if (value instanceof String){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueTypes.String.name()));
    } else {
      throw new RuntimeException("unexpected argument type");
    }
    return getIdFromStatementResult(result, "value");
  }

  /* --------------------------------------- */
  /* -                 Reading             - */
  /* --------------------------------------- */

  // targetSession is session number we are using to perform time travel.

  public void readMessage(final Session session, final long targetSession, final long actorId, final long causalMessageId) {
    getIdOfMessage(session, targetSession, actorId, causalMessageId);
  }

  public long getIdOfMessage(final Session session, final long targetSession, final long actorId, final long causalMessageId) {
    StatementResult result = session.run(matchActor + " " + "MATCH (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (SObject: SObject) - [:UPDATE *0..] -> (RootObject: SObject) - [:ROOT_OBJECT *] -> (actor) RETURN ID(turn)"
        , parameters("sessionId", targetSession, "actorId", actorId, "messageId", causalMessageId));

    return getIdFromStatementResult(result, "turn");
  }

  public Map<Short, Value> getTarget(final Session session, final long turnId){
    // get all slots of a turn. Information is stored in the object and the relationship
    StatementResult result = session.run("MATCH (turn: Turn) - [:TARGET] -> (SObject: SObject) where ID(turn)={turnId} MATCH (slot) - [r:SLOT]-> (SObject) return slot,r "
        , parameters("turnId", turnId));
    Map<Short, Value> slotMap = new HashMap<Short, Value>();
    for(Record record : result.list()){
      short slotId = (short) record.get("r").asRelationship().get("slotId").asInt();
      Value slotValue = record.get("slot");
      slotMap.put(slotId, slotValue);
    }
    return slotMap;
  }
}