package tools.timeTravelling;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.nodes.RootNode;

import som.VM;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.objectstorage.ClassFactory;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SBlock;
import som.vmobjects.SClass;
import som.vmobjects.SSymbol;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private VM vm;
  private Map<SSymbol, ClassFactory> factories;
  private Map<Long, RootNode> rootNodes;
  private Map<Long, SBlock> callbackBlocks;

  /*
   * map keeps track of revived SObject
   * This is necessary as we want to preserve the object state
   * Two objects pointing to the same third object should again point to the same object
   */
  private Map<Object, SAbstractObject> revivedObjects;
  private Map<SSymbol, SClass> revivedClasses;

  public TimeTravellingDebugger(final VM vm) {
    this.vm = vm;
    factories = new HashMap<SSymbol, ClassFactory>();
    rootNodes = new HashMap<Long, RootNode>();
    callbackBlocks = new HashMap<Long, SBlock>();
    revivedObjects = new HashMap<Object, SAbstractObject>();
    revivedClasses = new HashMap<SSymbol, SClass>();
  }
  /*
   *  Runtime information kept to make serialization easier
   */
  public void reportClassFactory(final ClassFactory factory) {
    this.factories.put(factory.getClassName(), factory);
  }
  public ClassFactory getFactory(final SSymbol name) {
    return this.factories.get(name);
  }

  public void reportRootNode(final long messageId, final RootNode rootNode) {
    rootNodes.put(messageId, rootNode);
  }
  public RootNode getRootNode(final long messageId) {
    return rootNodes.get(messageId);
  }

  public void reportSBlock(final Long messageId, final SBlock block) {
    callbackBlocks.put(messageId, block);
  }
  public SBlock getSBlock(final long messageId) {
    return callbackBlocks.get(messageId);
  }

  /*
   *  These methods are used to cache and lookup previously revived objects.
   */
  public synchronized void reportSAbstractObject(final Object dbRef, final SAbstractObject object) {
    revivedObjects.put(dbRef, object);
  }
  public synchronized SAbstractObject getSAbstractObject(final Object dbRef) {
    return revivedObjects.get(dbRef);
  }


  public void reportRevivedSClass(final SSymbol factoryName, final SClass revivedClass) {
    revivedClasses.put(factoryName, revivedClass);
  }
  public SClass getRevivedSClass(final SSymbol factoryName) {
    return revivedClasses.get(factoryName);
  }

  /*
   * actual methods to perform replay, once the system state is restored
   */
  public void replayMessage(final Actor timeTravelingActor,
      final EventualMessage msg) {
    timeTravelingActor.send(msg, vm.getActorPool());
  }
}
