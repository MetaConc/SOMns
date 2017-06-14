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
import som.vmobjects.SObjectWithClass;
import som.vmobjects.SSymbol;
import tools.timeTravelling.DatabaseInfo.DatabaseState;

public final class Database {
  private static Database singleton;
  private Driver driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "timetraveling"));

  private Database() {
    Session session = startSession();
    session.run("MATCH (a) DETACH DELETE a");
    StatementResult result = session.run("CREATE (nil:SClass {name: \"nil\"}) return nil");
    Classes.nilClass.updateDatabaseRef(getIdFromStatementResult(result.single().get("nil")));
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

  private Object getIdFromStatementResult(final Value value) {
    return value.asNode().id();
  }

  /* --------------------------------------- */
  /* -                 Writing             - */
  /* --------------------------------------- */

  public void createActor(final Session session, final Actor actor) {
    if(!actor.inDatabase) {
      session.run(" CREATE (actor:Actor {actorId: {actorId}})",
          parameters("actorId", actor.getId()));
      actor.inDatabase = true;
    }
  }

  public void storeFactoryMethod(final Session session, final Long messageId, final EventualMessage msg, final Actor actor, final SClass target, final int messageCount) {
    createActor(session, actor);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = session.run(
        "MATCH (actor: Actor {actorId: {actorId}})"
            + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}, messageCount: {messageCount}}) - [:TURN]-> (actor)"
            + " return turn",
            parameters("actorId", actor.getId(), "messageId", messageId, "messageName", msg.getSelector().getString(), "messageCount", messageCount));

    Record record = result.single();
    Object argumentId = record.get("turn").asNode().id();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++) {
      writeArgument(session, argumentId, i, args[i]);
    }
  }

  // the arguments of the message are already stored in the log.
  public void createCheckpoint(final Session session, final Long messageId, final EventualMessage msg,
      final Actor actor, final SObjectWithClass target, final int messageCount) {
    assert(actor.inDatabase); // Can't create actors from objects, first operation will always be a factoryMethod
    final DatabaseInfo.DatabaseState old = writeSObject(session, target);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = session.run(
        "MATCH (SObject: SObject) where ID(SObject) = {SObjectId}"
            + (old == DatabaseState.not_stored ? " MATCH (actor: Actor {actorId: {actorId}}) CREATE (SObject) - [:IN] -> (actor)" : "")
            + " CREATE (turn: Turn {messageId: {messageId}, messageName: {messageName}, messageCount: {messageCount}}) - [:TARGET] -> (SObject)"
            + " return turn",
            parameters("actorId", actor.getId(), "SObjectId", target.getDatabaseRef(), "messageId", messageId, "messageName", msg.getSelector().getString(),
                "messageCount", messageCount));

    // write all arguments to db
    Object argumentId = getIdFromStatementResult(result.single().get("turn"));
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++) {
      writeArgument(session, argumentId, i, args[i]);
    }
  }

  private void findOrCreateSClass(final Session session, final SClass sClass) {
    sClass.getLock();
    if (sClass.getDatabaseRef() == null) {

      SClass enclosing = sClass.getEnclosingObject().getSOMClass();
      findOrCreateSClass(session, enclosing);
      StatementResult result = session.run(
          "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
              + " CREATE (Child: SClass {factoryName: {factoryName}}) - [:ENCLOSED_BY]-> (SClass)"
              + " return Child",
              parameters("SClassId", enclosing.getDatabaseRef(), "factoryName", sClass.getName().getString()));
      final Object ref = getIdFromStatementResult(result.single().get("Child"));
      sClass.updateDatabaseRef(ref);
    }
    sClass.releaseLock();
  }

  private DatabaseInfo.DatabaseState writeSObject(final Session session, final SObjectWithClass object) {
    DatabaseInfo info = object.getDatabaseInfo();
    StatementResult result;
    DatabaseInfo.DatabaseState old = info.getState();
    switch(old) {
      case not_stored:
        SClass sClass = object.getSOMClass();
        findOrCreateSClass(session, sClass);
        result = session.run(
            "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
                + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:HAS_CLASS] -> (SClass)"
                + " CREATE (SObject) - [:HAS_ROOT] -> (SObject)"
                + " return SObject",
                parameters("SClassId", sClass.getDatabaseRef(), "type", SomValueType.SAbstractObject.name(), "version", info.getVersion()));
        object.updateDatabaseRef(getIdFromStatementResult(result.single().get("SObject")));
        writeSlots(session, object);
        break;
      case valid:
        break; // dirtying updated value is broken, for now always create copy if object was stored
      case outdated:
        result = session.run(
            "MATCH (old: SObject) where ID(old) = {oldRef}"
                + " MATCH (old) - [:HAS_ROOT] -> (root:SObject)"
                + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:UPDATE] -> (old)"
                + " CREATE (SObject) - [:HAS_ROOT] -> (root)"
                + " return SObject",
                parameters("oldRef", object.getDatabaseRef(), "type", SomValueType.SAbstractObject.name(), "version", info.getVersion()));
        object.updateDatabaseRef(getIdFromStatementResult(result.single().get("SObject")));
        writeSlots(session, object);
        break;
    }
    return old;
  }

  private Object writeFarReference(final Session session,
      final SFarReference farRef) {

    final Object ref = writeValue(session, farRef.getValue());
    if (ref != null) {
      StatementResult result = session.run(
          "MATCH (target) where ID(target)={targetId}"
              + " CREATE (value: FarRef) - [:POINTS_TO]->target"
              + " return value",
              parameters("targetId", ref));
      return getIdFromStatementResult(result.single().get("value"));
    }
    return null;
  }

  private void writeSlots(final Session session, final SObjectWithClass o) {
    if(o instanceof SObject){
      final SObject object = (SObject) o;
      final Object parentRef = object.getDatabaseRef();
      for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
        Object ref = writeValue(session, entry.getValue().read(object));
        if (ref != null) {
          session.run(
              "MATCH (parent) where ID(parent)={parentId}"
                  + " MATCH (slot) where ID(slot) = {slotRef}"
                  + "CREATE (slot) - [:SLOT {slotName: {slotName}}] -> (parent)",
                  parameters("parentId", parentRef, "slotRef", ref, "slotName", entry.getKey().getName().getString()));
        }
      }
    }
  }

  private void writeArgument(final Session session, final Object parentId, final int argIdx, final Object argValue) {
    Object ref = writeValue(session, argValue);
    if (ref != null) {
      session.run(
          "MATCH (parent) where ID(parent)={parentId}"
              + " MATCH (argument) where ID(argument) = {argRef}"
              + "CREATE (argument) - [:ARGUMENT {argIdx: {argIdx}}] -> (parent)",
              parameters("parentId", parentId, "argRef", ref, "argIdx", argIdx));
    }
  }

  private Object writeValue(final Session session, final Object value) {
    StatementResult result;
    if (value instanceof SFarReference) {
      return writeFarReference(session, (SFarReference) value);
    } else if (value instanceof SPromise) {
      throw new RuntimeException("not yet implemented: SPromise");
    } else if (value instanceof SResolver) {
      throw new RuntimeException("not yet implemented: SResolver");
    } else if (value instanceof SMutableObject || value instanceof SImmutableObject) {
      SObject object = (SObject) value;
      writeSObject(session, object);
      return object.getDatabaseRef();
    } else if (valueIsNil(value)) { // is it useful to store null values? can't we use closed world assumption?
      return null;
    } else if (value instanceof Long) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Long.name()));
    } else if (value instanceof Double) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Double.name()));
    } else if (value instanceof Boolean) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Boolean.name()));
    } else if (value instanceof String) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.String.name()));
    } else {
      throw new RuntimeException("unexpected argument type " + value.getClass());
    }
    return getIdFromStatementResult(result.single().get("value"));
  }

  /* --------------------------------------- */
  /* -                 Reading             - */
  /* --------------------------------------- */

  public SSymbol readMessageName(final Session session, final long actorId, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) - "
        + "[:TARGET] -> (SObject: SObject) - [:HAS_ROOT] -> (root: SObject) - [:IN] -> (actor: Actor {actorId: {actorId}}) "
        + "RETURN turn.messageName",
        parameters("actorId", actorId, "messageId", causalMessageId));

    return Symbols.symbolFor(result.single().get("turn.messageName").asString());
  }

  // expect the actor check to be done in readMessage name
  public Object[] readMessageArguments(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}})"
        + " MATCH (argument) - [idx:ARGUMENT] -> (turn)"
        + " return argument, idx",
        parameters("messageId", causalMessageId));
    List<Record> recordList = result.list();
    Object[] args = new Object[recordList.size() + 1]; // reserve space for target
    for (Record record : recordList) {
      Object argument = readValue(session, record.get("argument"));
      int argIdx = record.get("idx").get("argIdx").asInt();
      args[argIdx] = argument;
    }
    return args;
  }

  public SAbstractObject readTarget(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (SObject: SObject) RETURN SObject",
        parameters("messageId", causalMessageId));
    return readSObject(session, result.single().get("SObject").asNode());
  }

  private SAbstractObject readSObject(final Session session, final Node Object) {
    SAbstractObject sObject = ObjectReader.getSAbstractObject(Object.id());

    if(sObject == null) {
      // create the SClass object
      SClass sClass = getClassOfSObject(session, Object.id());

      // create the SObject
      sObject = NewObjectPrim.createEmptySObject(sClass);
      ObjectReader.reportSAbstractObject(Object.id(), sObject);
    }
    if (sObject instanceof SObject) { // not a SObjectWithoutFields
      DatabaseInfo info = ((SObject) sObject).getDatabaseInfo();
      int targetVersion = Object.get("version").asInt();
      if(!info.hasVersion(targetVersion)) {
        // if the version is different fill the slots
        fillSlots(session, Object.id(), (SObject) sObject);
        info.setVersion(targetVersion);
      }
    }
    return sObject;
  }

  private SClass getClassOfSObject(final Session session, final Object objectId) {
    StatementResult result = session.run(
        "MATCH (SObject: SObject) where ID(SObject)={objectId}"
            + " MATCH (root: SObject) <- [:HAS_ROOT]-(SObject)"
            + " MATCH (top: SClass {name: \"nil\"})"
            + " MATCH path = (root) - [:HAS_CLASS] -> (class) - [:ENCLOSED_BY*]->(top)"
            + " RETURN NODES(path)",
            parameters("objectId", objectId));
    Value value = result.single().get("NODES(path)");
    List<Object> nodes = value.asList(); // single because the path to nil class should be unique
    return reviveClass(nodes, 1);
  }

  private SClass reviveClass(final List<Object> factoryNames, final int idx) {
    Node node = (Node) factoryNames.get(idx);
    SSymbol factoryName = Symbols.symbolFor(node.get("factoryName").asString());
    SClass revivedClass = ObjectReader.getSClass(factoryName);

    if(revivedClass == null){
      if (idx == factoryNames.size() - 1) {
        return Classes.nilClass;
      }
      SClass outer = reviveClass(factoryNames, idx+1);
      revivedClass = ClassInstantiationNode.instantiate(outer, VM.getTimeTravellingDebugger().getFactory(factoryName));
      ObjectReader.reportSClass(factoryName, revivedClass);
    }
    return revivedClass;
  }

  private void fillSlots(final Session session, final Object objectId, final SObject sObject) {
    for (Entry<SlotDefinition, StorageLocation> entry : sObject.getObjectLayout().getStorageLocations().entrySet()) {
      String slotName = entry.getKey().getName().getString();
      StatementResult result = session.run("MATCH (SObject: SObject) where ID(SObject)={objectId} MATCH (slot) - [r:SLOT {slotName: {slotName}}]-> (SObject) return slot",
          parameters("objectId", objectId, "slotName", slotName));
      if (result.hasNext()) { // nils are not stored in the db
        Object slotValue = readValue(session, result.single().get("slot"));
        entry.getValue().write(sObject, slotValue);
      }
    }
  }

  private Object readValue(final Session session, final Value value) {
    String rawType = value.get("type").asString();
    SomValueType type = SomValueType.valueOf(rawType);
    switch(type){
      case SFarReference:
        throw new RuntimeException("not yet implemented");

      case SPromise:
        throw new RuntimeException("not yet implemented");

      case SResolver:
        throw new RuntimeException("not yet implemented");

      case SAbstractObject:
        return readSObject(session, value.asNode());
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
