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

public final class Database {
  private static Database singleton;
  private Driver driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "timetraveling"));

  private Database() {
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

  // the arguments of the message are already stored in the log.
  public void createCheckpoint(final Transaction transaction, final Long messageId, final EventualMessage msg, final Long actorId, final SMutableObject target) {
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = transaction.run("MATCH (actor: Actor {actorId: {actorId}}) "
        + "CREATE (turn: Turn {messageId: {id}, messageName: {messageName}}) "
        + "CREATE (turn)-[:TURN]->(actor)"
        + "CREATE (target:Object) "
        + "CREATE (target)-[:TARGETOF]->(turn) "
        + "return ID(target), ID(turn)"
        , parameters("actorId", actorId, "id", messageId, "messageName", msg.getSelector().getString()));

    Record record = result.single();

    // write all slots to db
    long targetId = record.get("ID(target)").asLong();
    HashMap<SlotDefinition, StorageLocation> locations = target.getObjectLayout().getStorageLocations();
    for (Entry<SlotDefinition, StorageLocation> entry : locations.entrySet()) {
      writeObject(transaction, targetId, entry.getKey(), entry.getValue().read(target));
    }

    // write all arguments to db
    long argumentId = record.get("ID(turn)").asLong();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }

  private void writeArgument(final Transaction transaction, final long parentId, final int argCount, final Object argValue) {
    Object value = null;
    if (argValue instanceof SFarReference) {
      writeArgument(transaction, parentId, argCount, ((SFarReference) argValue).getValue());
    } else if (argValue instanceof SPromise) {
      value = ((SPromise) argValue).getPromiseId();
    } else if (argValue instanceof SResolver) {
      value = ((SResolver) argValue).getPromise().getPromiseId();
    } else if (argValue instanceof SAbstractObject) {
      if(argValue instanceof SMutableObject){
        writeSObject(transaction, parentId, argCount, (SMutableObject) argValue);
        return;
      }  else if (valueIsNil(argValue)) { // is it useful to store null values? can't we use closed world assumption?
        return;
      } else {
        throw new RuntimeException("unexpected Sabstract argument type: " + argValue.getClass());
      }
    } else if (argValue instanceof Long||argValue instanceof Double||argValue instanceof Boolean||argValue instanceof String) {
      value = argValue;
    } else {
      throw new RuntimeException("unexpected argument type");
    }
    transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (parent)<-[:ARGUMENT]-(argument {argIdx: {argIdx}, value: {argValue}})",
        parameters("parentId", parentId, "argIdx", argCount, "argValue", value));
  }

  private void writeObject(final Transaction transaction, final long parentId, final SlotDefinition slotDef, final Object slotValue) {
    Object value = null;
    if (slotValue instanceof SFarReference) {
      writeObject(transaction, parentId, slotDef, ((SFarReference) slotValue).getValue());
    } else if (slotValue instanceof SPromise) {
      value = ((SPromise) slotValue).getPromiseId();
    } else if (slotValue instanceof SResolver) {
      value = ((SResolver) slotValue).getPromise().getPromiseId();
    } else if (slotValue instanceof SAbstractObject) {
      if(slotValue instanceof SMutableObject){
        writeSObject(transaction, parentId, slotDef, (SMutableObject) slotValue);
        return;
      } else if (valueIsNil(slotValue)) { // is it useful to store null values? can't we use closed world assumption?
        return;
      } else {
        throw new RuntimeException("unexpected Sabstract type: " + slotValue.getClass());
      }
    } else if (slotValue instanceof Long||slotValue instanceof Double||slotValue instanceof Boolean||slotValue instanceof String) {
      value = slotValue;
    } else {
      throw new RuntimeException("unexpected slot type");
    }
    transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (parent)<-[:SLOT]-(slot {slotId: {slotId}, slotName: {slotName}, value: {slotValue}})",
        parameters("parentId", parentId, "slotId", slotDef.getName().getSymbolId(), "slotName", slotDef.getName().getString(), "slotValue", value));
  }

  // Sobject is passed as argument or is stored in object, store it in db and return id of object
  private void writeSObject(final Transaction transaction, final Long parentId, final SlotDefinition slotDef, final SMutableObject slotValue){
    StatementResult result = transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (parent)<-[:SLOT]-(slot {slotId: {slotId}, slotName: {slotName}, type: {SObjectType}}) return ID(slot)"
        , parameters("parentId", parentId, "slotId", slotValue.getSOMClass().getName().getSymbolId(), "slotName", slotValue.getSOMClass().getName().getString(), "SObjectType", SObjectTypes.SMutable.id()));
    long slotId = getIdFromStatementResult(result,"slot");
    HashMap<SlotDefinition, StorageLocation> locations = slotValue.getObjectLayout().getStorageLocations();
    for (Entry<SlotDefinition, StorageLocation> entry : locations.entrySet()) {
      writeObject(transaction, slotId, entry.getKey(), entry.getValue().read(slotValue));
    }
  }

  // Sobject is passed as argument or is stored in object, store it in db and return id of object
  private void writeSObject(final Transaction transaction, final Long parentId, final int argCount, final SMutableObject argValue){
    StatementResult result = transaction.run("MATCH (parent) where ID(parent)={parentId} CREATE (parent)<-[:ARGUMENT]-(argument {argIdx: {argIdx}, type: {SObjectType}}) return ID(argument)",
        parameters("parentId", parentId, "argIdx", argCount, "SObjectType", SObjectTypes.SMutable.id()));
    long argumentId = getIdFromStatementResult(result,"argument");
    HashMap<SlotDefinition, StorageLocation> locations = argValue.getObjectLayout().getStorageLocations();
    for (Entry<SlotDefinition, StorageLocation> entry : locations.entrySet()) {
      writeObject(transaction, argumentId, entry.getKey(), entry.getValue().read(argValue));
    }
  }

  private long getIdFromStatementResult(final StatementResult result, final String name){
    return result.single().get("ID(" + name+")").asLong();
  }


  public void createActor(final Transaction transaction, final Actor actor) {
    transaction.run("CREATE (a:Actor {actorId: {id}})",
        parameters("id", actor.getId()));
  }

  public void createConstructor(final Transaction transaction, final Long messageId, final EventualMessage msg, final long actorId, final SClass target) {
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = transaction.run("MATCH (actor: Actor {actorId: {actorId}}) "
        + "CREATE (turn: Turn {messageId: {id}, messageName: {messageName}}) "
        + "CREATE (turn)-[:TURN]->(actor)"
         + "return ID(turn)"
        , parameters("actorId", actorId, "id", messageId, "messageName", msg.getSelector().getString()));

    Record record = result.single();
    long argumentId = record.get("ID(turn)").asLong();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++){
      writeArgument(transaction, argumentId, i, args[i]);
    }
  }
}
