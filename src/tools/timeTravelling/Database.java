package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;
import static som.vm.constants.Nil.valueIsNil;

import java.util.List;
import java.util.Map.Entry;

import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.Session;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.Value;
import org.neo4j.driver.v1.types.Node;

import som.VM;
import som.compiler.MixinDefinition.SlotDefinition;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.SFarReference;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.nodes.ClassInstantiationNode;
import som.interpreter.objectstorage.StorageLocation;
import som.primitives.NewObjectPrim;
import som.vm.Symbols;
import som.vm.constants.Classes;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SClass;
import som.vmobjects.SObject;
import som.vmobjects.SObject.SImmutableObject;
import som.vmobjects.SObject.SMutableObject;
import som.vmobjects.SSymbol;

public final class Database {
  private static Database singleton;
  private Driver driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "timetraveling"));

  private Database() {
    Session session = startSession();
    session.run("MATCH (a) DETACH DELETE a");
    StatementResult result = session.run("CREATE (nil:SClass {name: \"nil\"}) return ID(nil)");
    Classes.nilClass.setDatabaseRef(getIdFromStatementResult(result, "nil"));
    endSession(session);
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

  private enum SomValueType {
    SFarReference,
    SPromise,
    SResolver,
    SAbstractObject,
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

  private String matchActor = "MATCH (actor: Actor {actorId: {actorId}})";
  private String matchSObject = "MATCH (SObject: SObject) where ID(SObject) = {SObjectId}";
  private String matchSClass = "MATCH (SClass: SClass) where ID(SClass) = {SClassId}";


  /* --------------------------------------- */
  /* -                 Writing             - */
  /* --------------------------------------- */

  public void createActor(final Transaction transaction, final Actor actor) {
    transaction.run(" CREATE (actor:Actor {actorId: {actorId}})",
        parameters("actorId", actor.getId()));
    actor.inDatabase=true;
  }

  public void createConstructor(final Transaction transaction, final Long messageId, final EventualMessage msg, final long actorId, final SClass target) {
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = transaction.run(matchActor + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}}) - [:TURN]-> (actor)"
        + " return ID(turn)"
        , parameters("actorId", actorId, "messageId", messageId, "messageName", msg.getSelector().getString()));

    Record record = result.single();
    long argumentId = record.get("ID(turn)").asLong();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  // the arguments of the message are already stored in the log.
  public void createCheckpoint(final Transaction transaction, final Long messageId, final EventualMessage msg, final Long actorId, final SObject target) {
    final long ref = writeSObject(transaction, target);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = transaction.run(
        matchSObject + " " + matchActor
        + " CREATE (SObject) - [:IN] -> (actor)"
        + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}}) - [:TARGET] -> (SObject)"
        + " return ID(turn)"
        , parameters("actorId", actorId, "SObjectId", ref, "messageId", messageId, "messageName", msg.getSelector().getString()));

    // write all arguments to db
    long argumentId = getIdFromStatementResult(result, "turn");
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  private void findOrCreateSClass(final Transaction transaction, final SClass sClass){
    if(sClass.getDatabaseRef()==null){

      SClass enclosing = sClass.getEnclosingObject().getSOMClass();
      findOrCreateSClass(transaction, enclosing);
      StatementResult result = transaction.run(
          matchSClass
          + "CREATE (Child: SClass {factoryName: {factoryName}}) - [:ENCLOSED_BY]-> (SClass)"
          + " return ID(Child)",
          parameters("SClassId", enclosing.getDatabaseRef(), "factoryName", sClass.getName().getString()));
      final long ref = getIdFromStatementResult(result, "Child");
      sClass.setDatabaseRef(ref);
    }
  }

  private long writeSObject(final Transaction transaction, final SObject object){
    SClass sClass = object.getSOMClass();
    findOrCreateSClass(transaction, sClass);
    StatementResult result = transaction.run(
        matchSClass
        + " CREATE (SObject: SObject) - [:HAS_CLASS] -> (SClass)"
        + " return ID(SObject)",
        parameters("SClassId", sClass.getDatabaseRef()));
    Long ref = getIdFromStatementResult(result, "SObject");
    for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
      writeSlot(transaction, ref, entry.getKey(), entry.getValue().read(object));
    }
    return ref;
  }

  private Long writeFarReference(final Transaction transaction,
      final SFarReference farRef) {

    final Long ref = writeValue(transaction, farRef.getValue());
    if(ref != null){
      StatementResult result = transaction.run(
          "MATCH (target) where ID(target)={targetId}"
              + " CREATE (value: FarRef) - [:POINTS_TO]->target"
              + " return ID(value)"
              , parameters("targetId", ref));
      return getIdFromStatementResult(result, "value");
    }
    return null;
  }

  private void writeSlot(final Transaction transaction, final long parentId, final SlotDefinition slotDef, final Object slotValue) {
    Long ref = writeValue(transaction, slotValue);
    if(ref != null){
      transaction.run(
          "MATCH (parent) where ID(parent)={parentId}"
              + " MATCH (slot) where ID(slot) = {slotRef}"
              + "CREATE (slot) - [:SLOT {slotName: {slotName}}] -> (parent)",
              parameters("parentId", parentId, "slotRef", ref, "slotName", slotDef.getName().getString()));
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
      return writeFarReference(transaction, (SFarReference) value);
    } else if (value instanceof SPromise) {
      throw new RuntimeException("not yet implemented");
    } else if (value instanceof SResolver) {
      throw new RuntimeException("not yet implemented");
    } else if (value instanceof SMutableObject || value instanceof SImmutableObject){
      return writeSObject(transaction, (SObject) value);
    } else if (valueIsNil(value)) { // is it useful to store null values? can't we use closed world assumption?
      return null;
    } else if (value instanceof Long){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueType.Long.name()));
    } else if (value instanceof Double){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueType.Double.name()));
    } else if (value instanceof Boolean){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueType.Boolean.name()));
    } else if (value instanceof String){
      result = transaction.run("CREATE (value {value: {value}, type: {type}}) return ID(value)", parameters("value", value, "type", SomValueType.String.name()));
    } else {
      throw new RuntimeException("unexpected argument type " + value.getClass());
    }
    return getIdFromStatementResult(result, "value");
  }

  /* --------------------------------------- */
  /* -                 Reading             - */
  /* --------------------------------------- */

  public SSymbol readMessageName(final Session session, final long actorId, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (SObject: SObject) - [:IN] -> (actor: Actor {actorId: {actorId}}) RETURN turn.messageName"
        , parameters("actorId", actorId, "messageId", causalMessageId));

    return Symbols.symbolFor(result.single().get("turn.messageName").asString());
  }

  // expect the actor check to be done in readMessage name
  public Object[] readMessageArguments(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (SObject: SObject) RETURN ID(SObject)"
        , parameters("messageId", causalMessageId));

    final SAbstractObject target = readSObject(session, getIdFromStatementResult(result, "SObject"));
    Object[] arguments = readArguments(session, causalMessageId);
    arguments[0] = target;
    return arguments;
  }



  private Object[] readArguments(final Session session, final long messageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}})"
        + " MATCH (argument) - [idx:ARGUMENT] -> (turn)"
        + " return argument, idx",
        parameters("messageId", messageId));
    List<Record> recordList = result.list();
    Object[] args = new Object[recordList.size()+1]; // reserve one space for the target of message
    for(Record record : recordList) {
      Object argument = readValue(record.get("argument"));
      int argIdx = record.get("idx").get("argIdx").asInt();
      args[argIdx] = argument;
    }
    return args;
  }

  private SAbstractObject readSObject(final Session session, final long objectId) {

    // create the SClass object
    SClass sClass = getClassOfSObject(session, objectId);

    // create the SObject
    SAbstractObject sObject = NewObjectPrim.createEmptySObject(sClass);

    if(sObject instanceof SObject) { // not a SObjectWithoutFields
   // fill the slots
      fillSlots(session, objectId, (SObject) sObject);
    }
    return sObject;
  }

  private SClass getClassOfSObject(final Session session, final long objectId){
    StatementResult result= session.run(
        "MATCH (SObject: SObject) where ID(SObject)={objectId}"
            + "MATCH (top: SClass {name: \"nil\"})"
            + " MATCH path = (SObject) - [:HAS_CLASS] -> (class) - [:ENCLOSED_BY*]->(top)"
            + " RETURN NODES(path)"
            , parameters("objectId", objectId));
    Value value = result.single().get("NODES(path)");
    List<Object> nodes = value.asList(); // single because the path to nil class should be unique
    SClass sClass = Classes.nilClass;

    for(int i = nodes.size()-2; i >= 1; i--){ // first node is the turn, last node is nil
      Node node = (Node) nodes.get(i);
      SSymbol factoryName = Symbols.symbolFor(node.get("factoryName").asString());
      SClass recreatedClass = ClassInstantiationNode.instantiate(sClass, VM.getTimeTravellingDebugger().getFactory(factoryName));
      sClass = recreatedClass;
    }
    return sClass;
  }

  private void fillSlots(final Session session, final long objectId, final SObject sObject) {
    for(Entry<SlotDefinition, StorageLocation> entry : sObject.getObjectLayout().getStorageLocations().entrySet()){
      String slotName = entry.getKey().getName().getString();
      StatementResult result = session.run("MATCH (SObject: SObject) where ID(SObject)={objectId} MATCH (slot) - [r:SLOT {slotName: {slotName}}]-> (SObject) return slot",
          parameters("objectId", objectId, "slotName", slotName));
      if(result.hasNext()) { // nils are not stored in the db
        Object slotValue = readValue(result.single().get("slot"));
        entry.getValue().write(sObject, slotValue);
      }
    }
  }

  private Object readValue(final Value value) {
    SomValueType type = SomValueType.valueOf(value.get("type").asString());
    switch(type){
      case SFarReference:
        throw new RuntimeException("not yet implemented");

      case SPromise:
        throw new RuntimeException("not yet implemented");

      case SResolver:
        throw new RuntimeException("not yet implemented");

      case SAbstractObject:
        throw new RuntimeException("not yet implemented");

      case Long:
        return value.get("value").asLong();
      case Double:
        return value.get("value").asDouble();
      case Boolean:
        return value.get("value").asBoolean();
      case String:
        return value.get("value").asString();
      default:
        throw new RuntimeException("unexpected value type: " + type.name());
    }
  }
}