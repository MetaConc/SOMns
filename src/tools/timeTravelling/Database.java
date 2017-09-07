package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;

import java.util.Arrays;
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

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.RootNode;

import som.VM;
import som.compiler.MixinDefinition.SlotDefinition;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.actors.EventualMessage.DirectMessage;
import som.interpreter.actors.EventualMessage.PromiseCallbackMessage;
import som.interpreter.actors.EventualMessage.PromiseSendMessage;
import som.interpreter.actors.SFarReference;
import som.interpreter.actors.SPromise;
import som.interpreter.actors.SPromise.SResolver;
import som.interpreter.nodes.ClassInstantiationNode;
import som.interpreter.objectstorage.StorageLocation;
import som.primitives.NewObjectPrim;
import som.vm.Symbols;
import som.vm.constants.Classes;
import som.vm.constants.Nil;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SArray;
import som.vmobjects.SArray.PartiallyEmptyArray;
import som.vmobjects.SArray.PartiallyEmptyArray.Type;
import som.vmobjects.SArray.SImmutableArray;
import som.vmobjects.SArray.SMutableArray;
import som.vmobjects.SArray.STransferArray;
import som.vmobjects.SBlock;
import som.vmobjects.SClass;
import som.vmobjects.SObject;
import som.vmobjects.SObjectWithClass.SObjectWithoutFields;
import som.vmobjects.SSymbol;
import tools.timeTravelling.TimeTravellingActors.AbsorbingActor;
import tools.timeTravelling.TimeTravellingActors.TimeTravelActor;

public final class Database {
  private static Database singleton;
  private TimeTravellingDebugger timeTravellingDebugger;
  private Driver driver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("neo4j", "timetraveling"));
  private AbsorbingActor absorbingActor;
  private TimeTravelActor timeTravelingActor;

  private Database(final VM vm, final TimeTravellingDebugger timeTravellingDebugger) {
    this.timeTravellingDebugger = timeTravellingDebugger;
    absorbingActor = new TimeTravellingActors.AbsorbingActor(vm);
    timeTravelingActor = new TimeTravellingActors.TimeTravelActor(vm);
    Session session = startSession();
    session.run("MATCH (a) DETACH DELETE a");
    StatementResult result = session.run("CREATE (nil:SClass {name: \"nil\"}) return nil");
    Classes.nilClass.setDatabaseRef(getIdFromStatementResult(result.single().get("nil")));
    endSession(session);
  }

  public static void instantiateDatabase(final VM vm, final TimeTravellingDebugger timeTravellingDebugger) {
    assert (singleton == null);
    singleton = new Database(vm, timeTravellingDebugger);
  }

  // singleton design pattern
  public static synchronized Database getDatabaseInstance() {
    assert (singleton != null);
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
    String,
    SClass,
    Array,
    PartialArray;
  }

  public enum ArrayType {
    mutable,
    immutable,
    transfer;
  }

  private Object getIdFromStatementResult(final Value value) {
    return value.asNode().id();
  }

  /* --------------------------------------- */
  /* -                 Writing             - */
  /* --------------------------------------- */

  public void storeDirectMessageTurn(final Session session, final Long messageId, final DirectMessage msg) {
    Object ref = storeValue(session, msg.getArgs()[0]);
    msg.storeInDb(this, session);

    session.run(
        "MATCH (target) where ID(target) = {targetId}"
            + " MATCH (message) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (target)"
            + " CREATE (turn) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("targetId", ref, "messageId", messageId, "messageRef", msg.getDatabaseRef()));
  }

  public void storeSendMessageTurn(final Session session, final Long messageId, final PromiseSendMessage msg) {
    Object t = msg.getArgs()[0];
    Object targetRef = storeValue(session, t);
    msg.storeInDb(this, session);

    session.run(
        "MATCH (target) where ID(target) = {targetId}"
            + " MATCH (message) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (target)"
            + " CREATE (turn) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("targetId", targetRef, "messageId", messageId,
                "messageRef", msg.getDatabaseRef()));
  }

  public void storeCallbackMessageTurn(final Session session, final Long messageId, final PromiseCallbackMessage msg) {
    Object t = msg.getArgs()[0];
    assert (t instanceof SBlock);
    SBlock target = (SBlock) t;
    timeTravellingDebugger.reportSBlock(messageId, target);
    msg.storeInDb(this, session);

    session.run(
        "MATCH (message) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {messageId: {messageId}}) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("messageId", messageId, "messageRef", msg.getDatabaseRef()));
  }

  public void storeDirectMessage(final Session session, final DirectMessage msg,
      final long messageId, final SSymbol selector, final Object[] args,
      final SResolver resolver, final RootCallTarget onReceive) {
    RootNode rootNode = onReceive.getRootNode();
    timeTravellingDebugger.reportRootNode(messageId, rootNode);

    StatementResult result = session.run(
        " CREATE (message: DirectMessage {messageName: {messageName}, messageId: {messageId}})"
            + " return message",
            parameters("messageName", selector.getString(), "messageId", messageId));

    Object messageRef = result.single().get("message").asNode().id();
    msg.setDatabaseRef(messageRef);
    for (int i = 1; i < args.length; i++) { // ARG[0] is target, ie target of the turn this message belongs to
      storeArgument(session, messageRef, i, args[i]);
    }
    storeSResolver(session, resolver, messageRef);
  }

  public void storePromiseSendMessage(final Session session, final PromiseSendMessage msg,
      final long messageId, final SPromise originalTarget, final SSymbol selector,
      final Object[] args, final SResolver resolver, final RootCallTarget onReceive) {
    RootNode rootNode = onReceive.getRootNode();
    timeTravellingDebugger.reportRootNode(messageId, rootNode);

    StatementResult result = session.run(
        " CREATE (message: PromiseSendMessage {messageName: {messageName},  messageId: {messageId}})"
            + " return message",
            parameters("messageName", selector.getString(), "messageId", messageId));

    Object messageRef = result.single().get("message").asNode().id();
    msg.setDatabaseRef(messageRef);
    for (int i = 1; i < args.length; i++) { // ARG[0] is target, ie target of the turn this message belongs to
      storeArgument(session, messageRef, i, args[i]);
    }
    storeSPromise(session, originalTarget, messageRef);
    storeSResolver(session, resolver, messageRef);
  }

  public void storePromiseCallbackMessage(final Session session, final PromiseCallbackMessage msg,
      final long messageId, final SBlock callback, final SResolver resolver,
      final RootCallTarget onReceive, final SPromise promise, final Object target) {

    RootNode rootNode = onReceive.getRootNode();
    timeTravellingDebugger.reportRootNode(messageId, rootNode);
    timeTravellingDebugger.reportSBlock(messageId, callback);

    StatementResult result = session.run(
        " CREATE (message: PromiseCallbackMessage {messageId: {messageId}})"
            + " return message",
            parameters("messageId", messageId));

    Object messageRef = result.single().get("message").asNode().id();
    msg.setDatabaseRef(messageRef);
    storeSPromise(session, promise, messageRef);
    storeSResolver(session, resolver, messageRef);
    storeArgument(session, messageRef, 1, target); // promise callback message have 2 arguments: callback and the value which resolved the promise
  }

  public void storeSClass(final Session session, final SClass sClass) {
    Object ref = sClass.getDatabaseRef();
    if (ref == null) {
      SClass enclosing = sClass.getEnclosingObject().getSOMClass();
      enclosing.storeInDb(this, session);
      StatementResult result = session.run(
          "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
              + " CREATE (Child: SClass {factoryName: {factoryName}, type: {classType}}) - [:ENCLOSED_BY]-> (SClass)"
              + " return Child",
              parameters("SClassId", enclosing.getDatabaseRef(), "factoryName", sClass.getName().getString(), "classType", SomValueType.SClass.name()));
      ref = getIdFromStatementResult(result.single().get("Child"));
      sClass.setDatabaseRef(ref);
    }
  }

  // Store object (slots) together with class.
  // Class doesn't change, so the information about the class should only be stored once
  public void storeBaseObject(final Session session, final SObject object) {
    SClass sClass = object.getSOMClass();
    // only turns are stored in the db. If an object is created internally in a turn we might still need to store the class
    storeSClass(session, sClass);
    StatementResult result = session.run(
        "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
            + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:HAS_CLASS] -> (SClass)"
            + " CREATE (SObject) - [:HAS_ROOT] -> (SObject)"
            + " return SObject",
            parameters("SClassId", sClass.getDatabaseRef(), "type", SomValueType.SAbstractObject.name(), "version", 0));
    Object ref = getIdFromStatementResult(result.single().get("SObject"));
    object.setRoot(ref);
    linkSlots(session, object, ref);
  }

  public void storeRevisionObject(final Session session, final SObject object, final Object oldRef, final int version) {
    StatementResult result = session.run(
        "MATCH (old: SObject) - [:HAS_ROOT] -> (root:SObject) where ID(old) = {oldRef}"
            + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:UPDATE] -> (old)"
            + " CREATE (SObject) - [:HAS_ROOT] -> (root)"
            + " return SObject",
            parameters("oldRef", oldRef, "type", SomValueType.SAbstractObject.name(), "version", version));
    Object ref = getIdFromStatementResult(result.single().get("SObject"));
    object.update(ref);
    linkSlots(session, object, ref);
  }

//the slots have stored themselves while checking for dirty slots
 // create relation between object header and slots
 private void linkSlots(final Session session, final SObject object, final Object parentRef) {
   for (Entry<SlotDefinition, StorageLocation> entry : object.getObjectLayout().getStorageLocations().entrySet()) {
     Object ref = entry.getValue().getDatabaseRef();
     if (ref != null) {
       session.run(
           "MATCH (parent) where ID(parent)={parentId}"
               + " MATCH (slot) where ID(slot) = {slotRef}"
               + "CREATE (slot) - [:SLOT {slotName: {slotName}}] -> (parent)",
               parameters("parentId", parentRef, "slotRef", ref, "slotName", entry.getKey().getName().getString()));
     }
   }
 }

  // Far reference point to objects, far references can never be used in the same turn. Do not store the value.
  public void storeSFarReference(final Session session, final SFarReference farRef) {
    if (farRef.getDatabaseRef() == null) {
      StatementResult result = session.run("CREATE (farRef: SFarReference {type: {type}}) return farRef",
          parameters("type", SomValueType.SFarReference.name()));
      Object ref = getIdFromStatementResult(result.single().get("farRef"));
      farRef.setDatabaseRef(ref);
    }
  }

  // store SPromise and create link with parent
  private void storeSPromise(final Session session, final SPromise promise, final Object parentId) {
    promise.storeInDb(this, session);
    session.run("MATCH (parent) where ID(parent) = {parentId}"
        + "MATCH (promise: SPromise) where ID(promise) = {promiseId}"
        + "CREATE (parent) - [:HAS_PROMISE] -> (promise)",
        parameters("parentId", parentId, "promiseId", promise.getDatabaseRef()));
  }

  // called by promise in a synchronous manner
  public void storeSPromise(final Session session, final SPromise promise,
      final boolean explicitPromise) {
    Object ref = promise.getDatabaseRef();
    if (ref == null) {
      StatementResult result = session.run("CREATE (promise: SPromise {promiseId: {promiseId}, explicitPromise: {explicitPromise}, type: {type}}) return promise",
          parameters("promiseId", promise.getPromiseId(), "explicitPromise", explicitPromise, "type", SomValueType.SPromise.name()));
      ref = getIdFromStatementResult(result.single().get("promise"));
      promise.setDatabaseRef(ref);
    }
  }

  private void storeSResolver(final Session session, final SResolver resolver, final Object parentId) {
    if (resolver != null) {
      resolver.storeInDb(this, session);
      session.run("MATCH (parent) where ID(parent) = {parentId}"
          + "MATCH (resolver: SResolver) where ID(resolver) = {resolverId}"
          + "CREATE (parent) - [:HAS_RESOLVER] -> (resolver)",
          parameters("parentId", parentId, "resolverId", resolver.getDatabaseRef()));
    }
  }

  public void storeSResolver(final Session session, final SResolver resolver) {
    Object ref = resolver.getDatabaseRef();
    if (ref == null) {
      SPromise promise = resolver.getPromise();
      promise.storeInDb(this, session);
      StatementResult result = session.run(
          "MATCH (promise: SPromise) where ID(promise)={promiseId}" +
              "CREATE (resolver: SResolver {type: {resolverType}})-[:RESOLVER_OF]->(promise) return resolver",
              parameters("promiseId", promise.getDatabaseRef(), "resolverType", SomValueType.SResolver.name()));
      resolver.setDatabaseRef(getIdFromStatementResult(result.single().get("resolver")));
    }
  }

  public void SObjectWithoutFields(final Session session, final SObjectWithoutFields objectWithoutFields) {
    Object ref = objectWithoutFields.getDatabaseRef();
    if (ref == null) {
      SClass sClass = objectWithoutFields.getSOMClass();
      // only turns are stored in the db. If an object is created internally in a turn we might still need to store the class
      storeSClass(session, sClass);
      StatementResult result = session.run(
          "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
              + " CREATE (SObject: SObject {type: {type}, version: {version}}) - [:HAS_CLASS] -> (SClass)"
              + " CREATE (SObject) - [:HAS_ROOT] -> (SObject)"
              + " return SObject",
              parameters("SClassId", sClass.getDatabaseRef(), "type", SomValueType.SAbstractObject.name(), "version", 0));
      ref = getIdFromStatementResult(result.single().get("SObject"));
      objectWithoutFields.setDatabaseRef(ref);
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

  public void storeSArray(final Session session, final SArray array, final ArrayType type) {
    Object parentRef = null;

    if (array.isEmptyType()) {
      storeSEmptyArray(session, array, type);
    } else if (array.isBooleanType()) {
      storeBooleanArray(session, array, type);
    } else if (array.isDoubleType()) {
      storeDoubleArray(session, array, type);
    } else if (array.isLongType()) {
      storeLongArray(session, array, type);
    } else if (array.isPartiallyEmptyType()) {
      // can a immutable array be partiallyEmpty?
      storePartiallyEmptyArray(session, array, type);
    } else if (array.isObjectType()) {
      storeObjectArray(session, array, type);
    } else {
      throw new RuntimeException("unexpected array value type while storing");
    }
    array.setDatabaseRef(parentRef);
  }

  public void storeSEmptyArray(final Session session, final SArray array, final ArrayType type) {
    int length = (int) array.getStoragePlain();
    StatementResult result = session.run(
        "CREATE (array: SArray {length: {length}, type: {type}, arrayType: {arrayType}}) return array",
        parameters("length", length, "type", SomValueType.Array.name(), "arrayType", type.name()));
    Object ref = getIdFromStatementResult(result.single().get("array"));
    array.setDatabaseRef(ref);
  }

  public void storeBooleanArray(final Session session, final SArray array, final ArrayType type) {
    boolean[] storage = (boolean[]) array.getStoragePlain();
    StatementResult result = session.run(
        "CREATE (array: SArray {length: {length}, type: {type}, arrayType: {arrayType}, value: {value}}) return array",
        parameters("length", storage.length, "type", SomValueType.Array.name(), "arrayType", type.name(), "value", storage));
    Object ref = getIdFromStatementResult(result.single().get("array"));
    for (int i = 0; i < storage.length; i++) {
      storeSArrayElem(session, ref, i, storage[i]);
    }
    array.setDatabaseRef(ref);
  }

  public void storeLongArray(final Session session, final SArray array, final ArrayType type) {
    long[] storage = (long[]) array.getStoragePlain();
    StatementResult result = session.run(
        "CREATE (array: SArray {length: {length}, type: {type}, arrayType: {arrayType}, value: {value}}) return array",
        parameters("length", storage.length, "type", SomValueType.Array.name(), "arrayType", type.name(), "value", storage));
    Object ref = getIdFromStatementResult(result.single().get("array"));
    for (int i = 0; i < storage.length; i++) {
      storeSArrayElem(session, ref, i, storage[i]);
    }
    array.setDatabaseRef(ref);
  }

  public void storeDoubleArray(final Session session, final SArray array, final ArrayType type) {
    double[] storage = (double[]) array.getStoragePlain();
    StatementResult result = session.run(
        "CREATE (array: SArray {length: {length}, type: {type}, arrayType: {arrayType}}) return array",
        parameters("length", storage.length, "type", SomValueType.Array.name(), "arrayType", type.name()));
    Object ref = getIdFromStatementResult(result.single().get("array"));
    for (int i = 0; i < storage.length; i++) {
      storeSArrayElem(session, ref, i, storage[i]);
    }
    array.setDatabaseRef(ref);
  }

  public void storeObjectArray(final Session session, final SArray array, final ArrayType type) {
    Object[] storage = (Object[]) array.getStoragePlain();
    StatementResult result = session.run(
        "CREATE (array: SArray {length: {length}, type: {type}, arrayType: {arrayType}}) return array",
        parameters("length", storage.length, "type", SomValueType.Array.name(), "arrayType", type.name()));
    Object ref = getIdFromStatementResult(result.single().get("array"));
    for (int i = 0; i < storage.length; i++) {
      storeSArrayElem(session, ref, i, storage[i]);
    }
    array.setDatabaseRef(ref);
  }

  public void storePartiallyEmptyArray(final Session session, final SArray array, final ArrayType type) {
    PartiallyEmptyArray partial = (PartiallyEmptyArray) array.getStoragePlain();
    Object[] storage = partial.getStorage();
    StatementResult result = session.run(
        "CREATE (array: SArray {length: {length}, type: {type}, arrayType: {arrayType}, partialType: {partialType}, empty: {empty}}) return array",
        parameters("length", storage.length, "type", SomValueType.PartialArray.name(), "arrayType", type.name(), "partialType", partial.getType().name(), "empty", partial.getEmptyElements()));
    Object ref = getIdFromStatementResult(result.single().get("array"));
    for (int i = 0; i < storage.length; i++) {
      storeSArrayElem(session, ref, i, storage[i]);
    }
    array.setDatabaseRef(ref);
  }

  private void storeSArrayElem(final Session session, final Object parentId, final int arrayIdx, final Object arrayElem) {
    Object ref = storeValue(session, arrayElem);
    if (ref != null) {
      session.run(
          "MATCH (parent) where ID(parent)={parentId}"
              + " MATCH (elem) where ID(elem) = {elemRef}"
              + "CREATE (elem) - [:ARRAY_ELEM {idx: {idx}}] -> (parent)",
              parameters("parentId", parentId, "elemRef", ref, "idx", arrayIdx));
    }
  }

  public Object storeLong(final Session session , final Long value) {
    StatementResult result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Long.name()));
    return getIdFromStatementResult(result.single().get("value"));
  }

  public Object storeDouble(final Session session , final Double value) {
    StatementResult result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Double.name()));
    return getIdFromStatementResult(result.single().get("value"));
  }

  public Object storeBoolean(final Session session , final Boolean value) {
    StatementResult result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Boolean.name()));
    return getIdFromStatementResult(result.single().get("value"));
  }

  public Object storeString(final Session session , final String value) {
    StatementResult result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.String.name()));
    return getIdFromStatementResult(result.single().get("value"));
  }
  /*
   * Store value and return database ref to object
   */
  private Object storeValue(final Session session, final Object value) {
    StatementResult result;
    if (value instanceof SFarReference) {
      SFarReference farRef = (SFarReference) value;
      farRef.storeInDb(this, session);
      return farRef.getDatabaseRef();
    } else if (value instanceof SPromise) {
      SPromise promise = (SPromise) value;
      promise.storeInDb(this, session);
      return promise.getDatabaseRef();
    } else if (value instanceof SResolver) {
      SResolver resolver = (SResolver) value;
      resolver.storeInDb(this, session);
      return resolver.getDatabaseRef();
    } else if (Nil.valueIsNil(value)) {
      // is it not useful to store null values, use closed world assumption. If value is not in the database, that value is nil
      return null;
    } else if (value instanceof SObject) {
      SObject object = (SObject) value;
      object.isDirty(this, session);
      return object.getDatabaseRef();
    } else if (value instanceof SClass) {
      SClass sClass = (SClass) value;
      sClass.storeInDb(this, session);
      return sClass.getDatabaseRef();
    } else if (value instanceof SObjectWithoutFields) {
      SObjectWithoutFields fieldlessObject = (SObjectWithoutFields) value;
      fieldlessObject.storeInDb(this, session);
      return fieldlessObject.getDatabaseRef();
    } else if (value instanceof Long) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Long.name()));
    } else if (value instanceof Double) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Double.name()));
    } else if (value instanceof Boolean) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.Boolean.name()));
    } else if (value instanceof String) {
      result = session.run("CREATE (value {value: {value}, type: {type}}) return value", parameters("value", value, "type", SomValueType.String.name()));
    } else if (value instanceof SArray) {
      SArray array = (SArray) value;
      array.isDirty(this, session);
      return array.getDatabaseRef();
    } else {
      throw new RuntimeException("unexpected value type while storing " + value.getClass());
    }
    return getIdFromStatementResult(result.single().get("value"));
  }

  /* --------------------------------------- */
  /* -                 Reading             - */
  /* --------------------------------------- */

  public static void timeTravel(final long actorId, final long causalMessageId) {
    Database database = getDatabaseInstance();
    Session session = database.startSession();
    database.timeTravelingActor.setId(actorId); // the actor needs to have the same id as the original actor. This prevents changing id in the front end
    try {
      Record record = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:MESSAGE] -> (message) RETURN message",
          parameters("messageId", causalMessageId)).single();
      Node message = record.get("message").asNode();
      String methodType = message.labels().iterator().next();
      switch(methodType) {
        case "PromiseSendMessage": {
          PromiseSendMessage msg = database.readPromiseSendMessage(session, message, causalMessageId);
          database.timeTravellingDebugger.replayMessage(database.timeTravelingActor, msg);
          break;
        }
        case "PromiseCallbackMessage": {
          PromiseCallbackMessage msg = database.readPromiseCallbackMessage(session, message, causalMessageId);
          database.timeTravellingDebugger.replayMessage(database.timeTravelingActor, msg);
          break;
        }
        case "DirectMessage": {
          DirectMessage msg = database.readDirectMessage(session, message, causalMessageId);
          database.timeTravellingDebugger.replayMessage(database.timeTravelingActor, msg);
          break;
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    finally {
      database.endSession(session);
    }
  }

  private Object[] readMessageArguments(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:MESSAGE] -> (message) <- [idx:ARGUMENT]- (argument)"
        + " RETURN argument, idx",
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

  private DirectMessage readDirectMessage(final Session session, final Node messageNode, final long causalMessageId) {
    SSymbol selector = Symbols.symbolFor(messageNode.get("messageName").asString());
    Object[] arguments = readMessageArguments(session, causalMessageId);
    Object target = readValue(session, readTarget(session, causalMessageId));
    arguments[0] = target;
    SResolver resolver = readSResolver(session, messageNode.id());
    RootCallTarget onReceive = timeTravellingDebugger.getRootNode(causalMessageId).getCallTarget();
    return new DirectMessage(timeTravelingActor, selector, arguments, absorbingActor, resolver, onReceive, 0, -1, false, false);
  }

  private PromiseSendMessage readPromiseSendMessage(final Session session, final Node messageNode, final long causalMessageId) {
    SSymbol selector = Symbols.symbolFor(messageNode.get("messageName").asString());
    Object[] arguments = readMessageArguments(session, causalMessageId);
    SPromise targetPromise = readSPromise(session, messageNode.id());
    arguments[0] = targetPromise;
    SResolver resolver = readSResolver(session, messageNode.id());
    RootCallTarget onReceive = timeTravellingDebugger.getRootNode(causalMessageId).getCallTarget();
    PromiseSendMessage msg = EventualMessage.PromiseSendMessage.createForTimeTravel(selector, arguments, absorbingActor, resolver, onReceive, false, false);

    Value targetNode = readTarget(session, causalMessageId);
    Object targetValue = readValue(session, targetNode);
    SFarReference target = new SFarReference(timeTravelingActor, targetValue); // the target of our message needs to be owned by the time travel actor
    msg.resolve(target, timeTravelingActor, absorbingActor, -1, -1);
    return msg;
  }

  private PromiseCallbackMessage readPromiseCallbackMessage(final Session session, final Node messageNode, final long causalMessageId) {
    Actor owner = timeTravelingActor;
    SBlock callback = timeTravellingDebugger.getSBlock(causalMessageId);
    SResolver resolver = readSResolver(session, messageNode.id());
    RootCallTarget onReceive = timeTravellingDebugger.getRootNode(causalMessageId).getCallTarget();
    SPromise promiseRegisteredOn = readSPromise(session, messageNode.id());
    PromiseCallbackMessage msg = new PromiseCallbackMessage(owner, callback, resolver, onReceive, true, false, promiseRegisteredOn);

    Object resolution = readCallbackResolution(session, causalMessageId);
    msg.resolve(resolution, timeTravelingActor, absorbingActor, 0, 0);
    return msg;
  }

  private Object readCallbackResolution(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (message: PromiseCallbackMessage {messageId: {messageId}}) <- [:ARGUMENT]- (argument)"
        + " RETURN argument",
        parameters("messageId", causalMessageId));
    return readValue(session, result.single().get("argument"));
  }

  private SPromise readSPromise(final Session session, final long messageId) {
    final Node promiseNode = session.run("MATCH (message) where ID(message)={messageId}"
        + " MATCH (message) - [:HAS_PROMISE]->(promise: SPromise) RETURN promise",
        parameters("messageId", messageId)).single().get("promise").asNode();
    return readSPromise(promiseNode);
  }

  private SPromise readSPromise(final Node promiseNode) {
    boolean explicitPromise = promiseNode.get("explicitPromise").asBoolean();
    SPromise promise = SPromise.createPromiseForTimeTravel(timeTravelingActor, false, false, explicitPromise);
    return promise;
  }

  private SResolver readSResolver(final Session session, final long messageId) {
    final StatementResult result = session.run("MATCH (message) where ID(message)={messageId}"
        + " MATCH (message) - [:HAS_RESOLVER]->(resolver: SResolver) - [:RESOLVER_OF] -> (promise: SPromise) RETURN promise",
        parameters("messageId", messageId));
    if (result.hasNext()) {
      Node promiseNode = result.next().get("promise").asNode();
      return SPromise.createResolver(readSPromise(promiseNode));
    }
    return null;
  }

  private SResolver readSResolver(final Session session, final Node resolverNode) {
    StatementResult result = session.run("MATCH (resolver: SResolver) where ID(resolver)={resolverId} "
        + " MATCH (resolver) - [:RESOLVER_OF]->(promise:SPromise) RETURN promise",
        parameters("resolverId", resolverNode.id()));
    SPromise promise = readSPromise(result.single().get("promise").asNode());
    return SPromise.createResolver(promise);
  }

  private Value readTarget(final Session session, final long causalMessageId) {
    StatementResult result = session.run("MATCH (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (target) RETURN target",
        parameters("messageId", causalMessageId));
    return result.single().get("target");
  }

  private SAbstractObject readSObject(final Session session, final Node object) {
    SAbstractObject abstractObject = timeTravellingDebugger.getSAbstractObject(object.id());

    if (abstractObject == null) {
      // create the SClass object
      SClass sClass = getClassOfSObject(session, object.id());

      // create the SObject
      abstractObject = NewObjectPrim.createEmptySObject(sClass);
      timeTravellingDebugger.reportSAbstractObject(object.id(), abstractObject);
      if (abstractObject instanceof SObject) { // not a SObjectWithoutFields
        fillSlots(session, object.id(), (SObject) abstractObject);
      }
    }
    return abstractObject;
  }

  private SFarReference readSFarReference(final Session session, final Node object) {
    return new SFarReference(absorbingActor, 0);
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

  private SClass readSClass(final Session session, final Node node) {
    StatementResult result = session.run(
        "MATCH (base:SClass) WHERE id(base)={classId}"
            + " MATCH (top: SClass {name: \"nil\"})"
            + " MATCH path = (base) - [:ENCLOSED_BY*]->(top)"
            + " RETURN NODES(path)",
            parameters("classId", node.id()));
    Value value = result.single().get("NODES(path)");
    List<Object> nodes = value.asList(); // single because the path to nil class should be unique
    return reviveClass(nodes, 0);
  }

  private SClass reviveClass(final List<Object> factoryNames, final int idx) {
    Node node = (Node) factoryNames.get(idx);
    SSymbol factoryName = Symbols.symbolFor(node.get("factoryName").asString());
    SClass revivedClass = timeTravellingDebugger.getRevivedSClass(factoryName);

    if (revivedClass == null) {
      if (idx == factoryNames.size() - 1) {
        return Classes.nilClass;
      }
      SClass outer = reviveClass(factoryNames, idx + 1);
      revivedClass = ClassInstantiationNode.instantiate(outer, timeTravellingDebugger.getFactory(factoryName));
      timeTravellingDebugger.reportRevivedSClass(factoryName, revivedClass);
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

  private SArray readArray(final Session session, final Node node) {
    int length = node.get("length").asInt();
    ArrayType arrayType = ArrayType.valueOf(node.get("arrayType").asString());
    Object[] storage = new Object[length];
    fillArray(session, node.id(), storage);
    switch(arrayType) {
      case transfer:
        return new STransferArray(storage, Classes.transferArrayClass);
      case mutable:
        return new SMutableArray(storage, Classes.valueArrayClass);
      case immutable:
        return new SImmutableArray(storage, Classes.arrayClass);
      default:
        throw new RuntimeException("unexpected array type while reading: " + arrayType.name());
    }
  }

  private SArray readPartialArray(final Session session, final Node node) {
    int length = node.get("length").asInt();
    int empty = node.get("empty").asInt();
    Type partialType = Type.valueOf(node.get("partialType").asString());
    ArrayType arrayType = ArrayType.valueOf(node.get("arrayType").asString());
    Object[] rawStorage = new Object[length];
    Arrays.fill(rawStorage, Nil.nilObject);
    fillArray(session, node.id(), rawStorage);
    Object storage = new PartiallyEmptyArray(partialType, length, rawStorage, empty);
    switch(arrayType) {
      case transfer:
        return new STransferArray(storage, Classes.transferArrayClass);
      case mutable:
        return new SMutableArray(storage, Classes.valueArrayClass);
      case immutable:
        return new SImmutableArray(storage, Classes.arrayClass);
      default:
        throw new RuntimeException("unexpected array type while reading: " + arrayType.name());
    }
  }

  private void fillArray(final Session session, final Object arrayRef, final Object[] array) {
    StatementResult result = session.run("MATCH (array: SArray) where ID(array)={arrayRef} MATCH (arrayElem) - [idx:ARRAY_ELEM]-> (array) return arrayElem, idx",
        parameters("arrayRef", arrayRef));
    List<Record> recordList = result.list();
    for (Record record : recordList) {
      Object arrayElement = readValue(session, record.get("arrayElem"));
      int idx = record.get("idx").get("idx").asInt();
      array[idx] = arrayElement;
    }
  }

  private Object readValue(final Session session, final Value value) {
    SomValueType type = SomValueType.valueOf(value.get("type").asString());
    switch(type){
      case SFarReference:
        return readSFarReference(session, value.asNode());
      case SPromise:
        return readSPromise(value.asNode());
      case SResolver:
        return readSResolver(session, value.asNode());
      case SAbstractObject:
        return readSObject(session, value.asNode());
      case SClass:
        return readSClass(session, value.asNode());
      case Long:
        return value.get("value").asLong();
      case Double:
        return value.get("value").asDouble();
      case Boolean:
        return value.get("value").asBoolean();
      case String:
        return value.get("value").asString();
      case Array:
        return readArray(session, value.asNode());
      case PartialArray:
        return readPartialArray(session, value.asNode());
      default:
        throw new RuntimeException("unexpected value type while reading: " + type.name());
    }
  }
}
