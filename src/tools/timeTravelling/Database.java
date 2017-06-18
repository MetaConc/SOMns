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
    Classes.nilClass.getDatabaseInfo().setRoot(getIdFromStatementResult(result.single().get("nil")));
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

  private enum MethodType {
    factory,
    method;
  }

  private Object getIdFromStatementResult(final Value value) {
    return value.asNode().id();
  }

  /* --------------------------------------- */
  /* -                 Writing             - */
  /* --------------------------------------- */

  public void storeActor(final Session session, final Actor actor) {
    if(!actor.inDatabase) {
      session.run(" CREATE (actor:Actor {actorId: {actorId}})",
          parameters("actorId", actor.getId()));
      actor.inDatabase = true;
    }
  }

  public void storeFactoryMethod(final Session session, final Long messageId, final EventualMessage msg, final Actor actor, final SClass target, final int messageCount) {
    storeActor(session, actor);
    storeSClass(session, target);
    final Object messageRef = storeEventualMessage(session, msg);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    StatementResult result = session.run(
        "MATCH (actor: Actor {actorId: {actorId}})"
            + " MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
            + " MATCH (message: EventualMessage) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {type: {methodType}, messageId: {messageId}, messageCount: {messageCount}}) - [:TURN] -> (actor)"
            + " CREATE (turn) - [:TARGET] -> (SClass)"
            + " CREATE (turn) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("actorId", actor.getId(), "SClassId", target.getDatabaseInfo().getRef(), "methodType", MethodType.factory.name(),
                "messageId", messageId, "messageRef", messageRef, "messageCount", messageCount));

    Record record = result.single();
    Object argumentId = record.get("turn").asNode().id();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++) {
      storeArgument(session, argumentId, i, args[i]);
    }
  }

  // the arguments of the message are already stored in the log.
  public void storeCheckpoint(final Session session, final Long messageId, final EventualMessage msg,
      final Actor actor, final SObjectWithClass target, final int messageCount) {
    assert(actor.inDatabase); // Can't create actors from objects, first operation will always be a factoryMethod
    final DatabaseInfo.DatabaseState old = storeSObject(session, target);
    final Object messageRef = storeEventualMessage(session, msg);
    // create checkpoint header, root node to which all information of one turn becomes connected.
    session.run(
        "MATCH (SObject: SObject) where ID(SObject) = {SObjectId}"
            + " MATCH (message: EventualMessage) where ID(message)={messageRef}"
            + (old == DatabaseState.not_stored ? " MATCH (actor: Actor {actorId: {actorId}}) CREATE (SObject) - [:IN] -> (actor)" : "")
            + " CREATE (turn: Turn {type: {methodType}, messageId: {messageId}, messageCount: {messageCount}}) - [:TARGET] -> (SObject)"
            + " CREATE (turn) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("actorId", actor.getId(), "SObjectId", target.getDatabaseInfo().getRef(), "messageId", messageId,
                "messageCount", messageCount, "methodType", MethodType.method.name(), "messageRef", messageRef));
  }

  // TODO refactor
  private Object storeEventualMessage(final Session session, final EventualMessage msg) {
    boolean hasResolver = msg.getResolver() != null;
    Object resolverRef = null;
    if(hasResolver){
      resolverRef = storeSResolver(session, msg.getResolver());
    }
    StatementResult result = session.run(
        (hasResolver ? "MATCH (resolver: SResolver) where ID(resolver)={resolverId}" : "")
        + " CREATE (message: EventualMessage {messageName: {messageName}, causalId: {causalId}})"
        + (hasResolver ? "CREATE (message)-[:WITH_RESOLVER]-> (resolver)" : "")
        + " return message",
        parameters("messageName", msg.getSelector().getString(), "resolverId", resolverRef, "causalId", msg.getCausalMessageId()));
    Object messageId = result.single().get("message").asNode().id();
    Object[] args = msg.getArgs();
    for (int i = 1; i < args.length; i++) {
      storeArgument(session, messageId, i, args[i]);
    }
    return messageId;
  }

  private void storeSClass(final Session session, final SClass sClass) {
    DatabaseInfo info = sClass.getDatabaseInfo();
    info.getLock();
    if (info.getRef() == null) {

      SClass enclosing = sClass.getEnclosingObject().getSOMClass();
      storeSClass(session, enclosing);
      StatementResult result = session.run(
          "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
              + " CREATE (Child: SClass {factoryName: {factoryName}}) - [:ENCLOSED_BY]-> (SClass)"
              + " return Child",
              parameters("SClassId", enclosing.getDatabaseInfo().getRef(), "factoryName", sClass.getName().getString()));
      final Object ref = getIdFromStatementResult(result.single().get("Child"));
      info.setRoot(ref);
    }
    info.releaseLock();
  }

  private DatabaseInfo.DatabaseState storeSObject(final Session session, final SObjectWithClass object) {
    DatabaseInfo info = object.getDatabaseInfo();
    info.getLock();
    DatabaseInfo.DatabaseState old = info.getState();
    switch(old) {
      case not_stored:
        storeRoot(session, object, info);
        break;
      case valid:
        info.releaseLock();
        break;
      case outdated:
        info.releaseLock();
        StatementResult result = session.run(
            "MATCH (old: SObject) where ID(old) = {oldRef}"
                + " MATCH (old) - [:HAS_ROOT] -> (root:SObject)"
                + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:UPDATE] -> (old)"
                + " CREATE (SObject) - [:HAS_ROOT] -> (root)"
                + " return SObject",
                parameters("oldRef", info.getRef(), "type", SomValueType.SAbstractObject.name(), "version", info.getVersion()));
        info.update(getIdFromStatementResult(result.single().get("SObject")));
        storeSlots(session, object);
        break;
    }
    return old;
  }

  private void storeRoot(final Session session, final SObjectWithClass object, final DatabaseInfo info){
    SClass sClass = object.getSOMClass();
    // only turns are stored in the db. If an object is created internally in a turn we might still need to store the class
    storeSClass(session, sClass);
    StatementResult result = session.run(
        "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
            + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:HAS_CLASS] -> (SClass)"
            + " CREATE (SObject) - [:HAS_ROOT] -> (SObject)"
            + " return SObject",
            parameters("SClassId", sClass.getDatabaseInfo().getRef(), "type", SomValueType.SAbstractObject.name(), "version", 0));
    info.setRoot(getIdFromStatementResult(result.single().get("SObject")));
    info.releaseLock();
    storeSlots(session, object);
  }

  // Far reference point to SObjects.
  // Either the object is already in the database, in which case the far ref points to the root object
  // Or the object is not in the database and we add it.
  // Since far references point to objects in other actors we need locks.
  private Object storeSFarReference(final Session session, final SFarReference farRef) {
    final Object val = farRef.getValue();
    assert(val instanceof SObjectWithClass);
    SObjectWithClass value = (SObject) val;
    DatabaseInfo info = value.getDatabaseInfo();
    info.getLock();
    if(info.getState()==DatabaseState.not_stored) {
      storeRoot(session, value, info);
    }
    info.releaseLock();
    Object targetRef = info.getRoot();

    StatementResult result = session.run(
          "MATCH (target) where ID(target)={targetId}"
              + " CREATE (farRef: SFarReference) - [:FAR_REFERENCE_TO]->target"
              + " return farRef",
              parameters("targetId", targetRef));
      return getIdFromStatementResult(result.single().get("farRef"));
  }

  private Object storeSPromise(final Session session, final SPromise promise) {
    StatementResult result = session.run("CREATE (promise: SPromise) return promise");
    Object ref = getIdFromStatementResult(result.single().get("promise"));
    promise.getDatabaseInfo().setRoot(ref);
    return ref;
  }

  private Object storeSResolver(final Session session, final SResolver resolver) {
    Object promiseRef = storeSPromise(session, resolver.getPromise());
    StatementResult result = session.run(
        "MATCH (promise: SPromise) where ID(promise)={promiseId}"+
        "CREATE (resolver: SResolver)-[:RESOLVER_OF]->(promise) return resolver",
        parameters("promiseId", promiseRef));
    return getIdFromStatementResult(result.single().get("resolver"));
  }

  private void storeSlots(final Session session, final SObjectWithClass o) {
    if(o instanceof SObject){
      final SObject object = (SObject) o;
      final Object parentRef = object.getDatabaseInfo().getRef();
      for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
        Object ref = storeValue(session, entry.getValue().read(object));
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

  private void storeArgument(final Session session, final Object parentId, final int argIdx, final Object argValue) {
    Object ref = storeValue(session, argValue);
    if (ref != null) {
      session.run(
          "MATCH (parent) where ID(parent)={parentId}"
              + " MATCH (argument) where ID(argument) = {argRef}"
              + "CREATE (argument) - [:ARGUMENT {argIdx: {argIdx}}] -> (parent)",
              parameters("parentId", parentId, "argRef", ref, "argIdx", argIdx));
    }
  }

  private Object storeValue(final Session session, final Object value) {
    StatementResult result;
    if (value instanceof SFarReference) {
      return storeSFarReference(session, (SFarReference) value);
    } else if (value instanceof SPromise) {
      return storeSPromise(session, (SPromise) value);
    } else if (value instanceof SResolver) {
      return storeSResolver(session, (SResolver) value);
    } else if (value instanceof SMutableObject || value instanceof SImmutableObject) {
      SObject object = (SObject) value;
      storeSObject(session, object);
      return object.getDatabaseInfo().getRef();
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
      throw new RuntimeException("unexpected argument type while storing " + value.getClass());
    }
    return getIdFromStatementResult(result.single().get("value"));
  }

  /* --------------------------------------- */
  /* -                 Reading             - */
  /* --------------------------------------- */

  public static void prepareForTimeTravel(final long actorId, final long causalMessageId) {
    try {

      Database database = getDatabaseInstance();
      Session session = database.startSession();

      Record record = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:MESSAGE] -> (message: EventualMessage) RETURN turn, message",
          parameters("messageId", causalMessageId)).single();
      Node turn = record.get("turn").asNode();
      Node message = record.get("message").asNode();
      SSymbol messageName = Symbols.symbolFor(message.get("messageName").asString());
      Object[] arguments = database.readMessageArguments(session, causalMessageId);
      MethodType methodType = MethodType.valueOf(turn.get("type").asString());
      switch(methodType) {
        case method: {
          SAbstractObject target = database.readTarget(session, causalMessageId);
          arguments[0] = target;
          TimeTravellingDebugger.replayMethod(messageName, target, arguments);
          break;
        }
        case factory: {
          SClass target = database.readSClassAsTarget(session, causalMessageId);
          arguments[0] = target;
          TimeTravellingDebugger.replayFactory(messageName, target, arguments);

        }
      }
      database.endSession(session);
    } catch (Exception e) {
      VM.errorPrint(e.getMessage());
      e.printStackTrace();
    }

  }

  // expect the actor check to be done in readMessage name
  public Object[] readMessageArguments(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) <- [idx:ARGUMENT]- (argument)"
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
    SAbstractObject sObject = TimeTravellingDebugger.getSAbstractObject(Object.id());

    if(sObject == null) {
      // create the SClass object
      SClass sClass = getClassOfSObject(session, Object.id());

      // create the SObject
      sObject = NewObjectPrim.createEmptySObject(sClass);
      TimeTravellingDebugger.reportSAbstractObject(Object.id(), sObject);
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

  private SFarReference readSFarReference(final Session session, final Node Object) {
    StatementResult result = session.run("MATCH (farRef: SFarReference) where ID(farRef)={farRefId}" +
       "MATCH (farRef) - [:FAR_REFERENCE_TO]->(target: SObject)-[:in]->(actor: Actor)" +
       "return target, actor", parameters());
    Record record = result.single();
    SAbstractObject target = readSObject(session, record.get("target").asNode());
    Actor actor = readActor(session, record.get("actor").asNode());

    return new SFarReference(actor, target);
  }

  private Actor readActor(final Session session, final Node actorNode) {
    Long actorId = actorNode.get("actorId").asLong();
    Actor revivedActor = TimeTravellingDebugger.getActor(actorId);
    if(revivedActor==null){
      revivedActor = Actor.createActor();
      TimeTravellingDebugger.reportActor(actorId, revivedActor);
    }
    return revivedActor;
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

  private SClass readSClassAsTarget(final Session session, final long causalMessageId) {
    StatementResult result = session.run(
        "MATCH (turn: Turn {messageId: {messageId}}) - [:TARGET]->(base:SClass)"
            + " MATCH (top: SClass {name: \"nil\"})"
            + " MATCH path = (base) - [:ENCLOSED_BY*]->(top)"
            + " RETURN NODES(path)",
            parameters("messageId", causalMessageId));
    Value value = result.single().get("NODES(path)");
    List<Object> nodes = value.asList(); // single because the path to nil class should be unique
    return reviveClass(nodes, 0);
  }

  private SClass reviveClass(final List<Object> factoryNames, final int idx) {
    Node node = (Node) factoryNames.get(idx);
    SSymbol factoryName = Symbols.symbolFor(node.get("factoryName").asString());
    SClass revivedClass = TimeTravellingDebugger.getSClass(factoryName);

    if(revivedClass == null){
      if (idx == factoryNames.size() - 1) {
        return Classes.nilClass;
      }
      SClass outer = reviveClass(factoryNames, idx+1);
      revivedClass = ClassInstantiationNode.instantiate(outer, VM.getTimeTravellingDebugger().getFactory(factoryName));
      TimeTravellingDebugger.reportSClass(factoryName, revivedClass);
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
    SomValueType type = SomValueType.valueOf(value.get("type").asString());
    switch(type){
      case SFarReference:
        return readSFarReference(session, value.asNode());
      case SPromise:
        throw new RuntimeException("not yet implemented: read SPromise");

      case SResolver:
        throw new RuntimeException("not yet implemented: read SResolver");

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
        throw new RuntimeException("unexpected value typ;e while reading: " + type.name());
    }
  }
}
