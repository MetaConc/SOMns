package tools.timeTravelling;

import static org.neo4j.driver.v1.Values.parameters;

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
import som.vmobjects.SArray.SImmutableArray;
import som.vmobjects.SArray.SMutableArray;
import som.vmobjects.SArray.STransferArray;
import som.vmobjects.SBlock;
import som.vmobjects.SClass;
import som.vmobjects.SObject;
import som.vmobjects.SObject.SImmutableObject;
import som.vmobjects.SObject.SMutableObject;
import som.vmobjects.SObjectWithClass;
import som.vmobjects.SObjectWithClass.SObjectWithoutFields;
import som.vmobjects.SSymbol;
import tools.timeTravelling.DatabaseInfo.DatabaseState;
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
    Classes.nilClass.getDatabaseInfo().setRoot(getIdFromStatementResult(result.single().get("nil")));
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
    Array;
  }

  private enum ArrayType {
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
    storeEventualMessage(session, msg);

    session.run(
        "MATCH (target) where ID(target) = {targetId}"
            + " MATCH (message) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (target)"
            + " CREATE (turn) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("targetId", ref, "messageId", messageId, "messageRef", msg.getDatabaseInfo().getRef()));
  }

  public void storeSendMessageTurn(final Session session, final Long messageId, final PromiseSendMessage msg) {
    Object t = msg.getArgs()[0];
    Object targetRef = storeValue(session, t);
    storeEventualMessage(session, msg);

    session.run(
        "MATCH (target) where ID(target) = {targetId}"
            + " MATCH (message) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {messageId: {messageId}}) - [:TARGET] -> (target)"
            + " CREATE (turn) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("targetId", targetRef, "messageId", messageId,
                "messageRef", msg.getDatabaseInfo().getRef()));
  }

  public void storeCallbackMessageTurn(final Session session, final Long messageId, final PromiseCallbackMessage msg) {
    Object t = msg.getArgs()[0];
    assert (t instanceof SBlock);
    SBlock target = (SBlock) t;
    timeTravellingDebugger.reportSBlock(messageId, target);
    storeEventualMessage(session, msg);

    session.run(
        "MATCH (message) where ID(message)={messageRef}"
            + " CREATE (turn: Turn {messageId: {messageId}}) - [:MESSAGE]->(message)"
            + " return turn",
            parameters("messageId", messageId, "messageRef", msg.getDatabaseInfo().getRef()));
  }

  private void storeEventualMessage(final Session session, final EventualMessage msg) {
    if (msg.getDatabaseInfo().getState() != DatabaseState.valid) {
      msg.storeInDb(this, session);
    }
  }

  public void storeDirectMessage(final Session session, final DatabaseInfo info,
      final long messageId, final SSymbol selector, final Object[] args,
      final SResolver resolver, final RootCallTarget onReceive) {

    RootNode rootNode = onReceive.getRootNode();
    timeTravellingDebugger.reportRootNode(messageId, rootNode);

    StatementResult result = session.run(
        " CREATE (message: DirectMessage {messageName: {messageName}, messageId: {messageId}})"
            + " return message",
            parameters("messageName", selector.getString(), "messageId", messageId));

    Object messageRef = result.single().get("message").asNode().id();
    info.setRoot(messageRef);
    for (int i = 1; i < args.length; i++) { // ARG[0] is target, ie target of the turn this message belongs to
      storeArgument(session, messageRef, i, args[i]);
    }
    storeSResolver(session, resolver, messageRef);
  }

  public void storePromiseSendMessage(final Session session,
      final long messageId, final DatabaseInfo info, final SPromise originalTarget,
      final SSymbol selector, final Object[] args, final SResolver resolver, final RootCallTarget onReceive) {
    RootNode rootNode = onReceive.getRootNode();
    timeTravellingDebugger.reportRootNode(messageId, rootNode);

    StatementResult result = session.run(
        " CREATE (message: PromiseSendMessage {messageName: {messageName},  messageId: {messageId}})"
            + " return message",
            parameters("messageName", selector.getString(), "messageId", messageId));

    Object messageRef = result.single().get("message").asNode().id();
    info.setRoot(messageRef);
    for (int i = 1; i < args.length; i++) { // ARG[0] is target, ie target of the turn this message belongs to
      storeArgument(session, messageRef, i, args[i]);
    }
    storeSPromise(session, originalTarget, messageRef);
    storeSResolver(session, resolver, messageRef);
  }

  public void storePromiseCallbackMessage(final Session session, final DatabaseInfo info,
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
    info.setRoot(messageRef);
    storeSPromise(session, promise, messageRef);
    storeSResolver(session, resolver, messageRef);
    storeArgument(session, messageRef, 1, target); // promise callback message have 2 arguments: callback and the value which resolved the promise
  }

  private Object storeSClass(final Session session, final SClass sClass) {
    DatabaseInfo info = sClass.getDatabaseInfo();
    info.getLock();
    Object ref = info.getRef();
    if (ref == null) {

      SClass enclosing = sClass.getEnclosingObject().getSOMClass();
      Object enclosingRef = storeSClass(session, enclosing);
      StatementResult result = session.run(
          "MATCH (SClass: SClass) where ID(SClass) = {SClassId}"
              + " CREATE (Child: SClass {factoryName: {factoryName}, type: {classType}}) - [:ENCLOSED_BY]-> (SClass)"
              + " return Child",
              parameters("SClassId", enclosingRef, "factoryName", sClass.getName().getString(), "classType", SomValueType.SClass.name()));
      ref = getIdFromStatementResult(result.single().get("Child"));
      info.setRoot(ref);
    }
    info.releaseLock();
    return ref;
  }

  private void storeSObject(final Session session, final SObjectWithClass object) {
    DatabaseInfo info = object.getDatabaseInfo();
    info.getLock();
    switch(info.getState()) {
      case not_stored:
        storeBaseObject(session, object, info);
        break;
      case valid:
        break;
      case outdated:
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
    info.releaseLock();
  }

  // Store object (slots) together with class.
  // Class doesn't change, so the information about the class should only be stored once
  private void storeBaseObject(final Session session, final SObjectWithClass object, final DatabaseInfo info) {
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
    storeSlots(session, object);
  }

  // Far reference point to objects, add or find the object in the database, create point to it.
  // Since far references point to objects in other actors we need locks?.
  private Object storeSFarReference(final Session session, final SFarReference farRef) {
    System.out.println("far ref");
    final Object val = farRef.getValue();
    Object targetRef = storeValue(session, val);
    StatementResult result = session.run(
        "MATCH (target) where ID(target)={targetId}"
            + " CREATE (farRef: SFarReference) - [:FAR_REFERENCE_TO]->(target)"
            + " return farRef",
            parameters("targetId", targetRef));
    return getIdFromStatementResult(result.single().get("farRef"));
  }

  // store SPromise and create link with parent
  private void storeSPromise(final Session session, final SPromise promise, final Object parentId) {
    promise.storeInDb(this, session);
    session.run("MATCH (parent) where ID(parent) = {parentId}"
        + "MATCH (promise: SPromise) where ID(promise) = {promiseId}"
        + "CREATE (parent) - [:HAS_PROMISE] -> (promise)",
        parameters("parentId", parentId, "promiseId", promise.getDatabaseInfo().getRef()));
  }

  public void storeSPromise(final Session session, final SPromise promise,
      final boolean explicitPromise) {

    Object ref = promise.getDatabaseInfo().getRoot();
    if (ref == null) {
      StatementResult result = session.run("CREATE (promise: SPromise {promiseId: {promiseId}, explicitPromise: {explicitPromise}, type: {type}}) return promise",
          parameters("promiseId", promise.getPromiseId(), "explicitPromise", explicitPromise, "type", SomValueType.SPromise.name()));
      ref = getIdFromStatementResult(result.single().get("promise"));
      promise.getDatabaseInfo().setRoot(ref);
    }
  }

  private void storeSResolver(final Session session, final SResolver resolver, final Object parentId) {
    if (resolver != null) {
      Object resolverRef = storeSResolver(session, resolver);
      session.run("MATCH (parent) where ID(parent) = {parentId}"
          + "MATCH (resolver: SResolver) where ID(resolver) = {resolverId}"
          + "CREATE (parent) - [:HAS_RESOLVER] -> (resolver)",
          parameters("parentId", parentId, "resolverId", resolverRef));
    }
  }

  private Object storeSResolver(final Session session, final SResolver resolver) {
    SPromise promise = resolver.getPromise();
    promise.storeInDb(this, session);
    StatementResult result = session.run(
        "MATCH (promise: SPromise) where ID(promise)={promiseId}" +
            "CREATE (resolver: SResolver)-[:RESOLVER_OF]->(promise) return resolver",
            parameters("promiseId", promise.getDatabaseInfo().getRef()));
    return getIdFromStatementResult(result.single().get("resolver"));
  }

  private void storeSlots(final Session session, final SObjectWithClass o) {
    if (o instanceof SObject) {
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

  private Object storeSArray(final Session session, final SArray array) {
    Object parentRef;
    ArrayType type;

    //TODO here
    if(array instanceof STransferArray) {
      type = ArrayType.transfer;
    } else if (array instanceof SMutableArray) {
      type = ArrayType.mutable;
    } else if (array instanceof SImmutableArray) {
      type = ArrayType.immutable;
    } else {
      throw new RuntimeException("unexpected array type while storing");
    }

    if(array.isEmptyType()){
      parentRef = storeSArrayHeader(session, (int) array.getStoragePlain(), type);
    } else if(array.isBooleanType()) {
      boolean[] storage = (boolean[]) array.getStoragePlain();
      parentRef = storeSArrayHeader(session, storage.length, type);
      for (int i = 0; i < storage.length; i++) {
        storeSArrayElem(session, parentRef, i, storage[i]);
      }
    } else if(array.isDoubleType()) {
      double[] storage = (double[]) array.getStoragePlain();
      parentRef = storeSArrayHeader(session, storage.length, type);
      for (int i = 0; i < storage.length; i++) {
        storeSArrayElem(session, parentRef, i, storage[i]);
      }
    } else if(array.isLongType()) {
      long[] storage = (long[]) array.getStoragePlain();
      parentRef = storeSArrayHeader(session, storage.length, type);
      for (int i = 0; i < storage.length; i++) {
        storeSArrayElem(session, parentRef, i, storage[i]);
      }
    } else if(array.isPartiallyEmptyType()) {
      Object[] storage = ((PartiallyEmptyArray) array.getStoragePlain()).getStorage();
      parentRef = storeSArrayHeader(session, storage.length, type);
      for (int i = 0; i < storage.length; i++) {
        storeSArrayElem(session, parentRef, i, storage[i]);
      }
    } else if(array.isObjectType()) {
      Object[] storage = (Object[]) array.getStoragePlain();
      parentRef = storeSArrayHeader(session, storage.length, type);
      for (int i = 0; i < storage.length; i++) {
        storeSArrayElem(session, parentRef, i, storage[i]);
      }
    } else {
      throw new RuntimeException("unexpected array value type while storing");
    }
    return parentRef;
  }

  private Object storeSArrayHeader(final Session session, final int length, final ArrayType arrayType){
    StatementResult result = session.run(
        "CREATE (arrayHeader: SArray {length: {length}, type: {type}, arrayType: {arrayType}}) return arrayHeader",
        parameters("length", length, "type", SomValueType.Array.name(), "arrayType", arrayType.name()));
    return getIdFromStatementResult(result.single().get("arrayHeader"));
  }

  private void storeSArrayElem(final Session session, final Object parentId, final int arrayIdx, final Object arrayElem) {
    Object ref = storeValue(session, arrayElem);
    session.run(
        "MATCH (parent) where ID(parent)={parentId}"
            + " MATCH (elem) where ID(elem) = {elemRef}"
            + "CREATE (elem) - [:ARRAY_ELEM {idx: {idx}}] -> (parent)",
            parameters("parentId", parentId, "elemRef", ref, "idx", arrayIdx));
  }
  /*
   * Store value and return database ref to object
   */
  private Object storeValue(final Session session, final Object value) {
    StatementResult result;
    if (value instanceof SFarReference) {
      return storeSFarReference(session, (SFarReference) value);
    } else if (value instanceof SPromise) {
      SPromise promise = (SPromise) value;
      promise.storeInDb(this, session);
      return promise.getDatabaseInfo().getRef();
    } else if (value instanceof SResolver) {
      return storeSResolver(session, (SResolver) value);
    } else if (Nil.valueIsNil(value)) {
      // is it not useful to store null values, use closed world assumption. If value is not in the database, that value is nil
      return null;
    } else if (value instanceof SMutableObject || value instanceof SImmutableObject || value instanceof SObjectWithoutFields) {
      SObjectWithClass object = (SObjectWithClass) value;
      storeSObject(session, object);
      return object.getDatabaseInfo().getRef();
    } else if (value instanceof SClass) {
      SClass sClass = (SClass) value;
      return storeSClass(session, sClass);
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
      return storeSArray(session, array);
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
    if(result.hasNext()) {
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
    StatementResult result = session.run("MATCH (farRef: SFarReference) where ID(farRef)={farRefId}" +
        "MATCH (farRef) - [:FAR_REFERENCE_TO]->(target)" +
        "return target", parameters());
    Record record = result.single();
    Object target = readValue(session, record.get("target"));
    return new SFarReference(absorbingActor, target);
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
      default:
        throw new RuntimeException("unexpected value type while reading: " + type.name());
    }
  }
}
