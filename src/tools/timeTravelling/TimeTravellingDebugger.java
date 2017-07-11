package tools.timeTravelling;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

import som.VM;
import som.compiler.AccessModifier;
import som.interpreter.actors.Actor;
import som.interpreter.actors.EventualMessage;
import som.interpreter.nodes.dispatch.Dispatchable;
import som.interpreter.objectstorage.ClassFactory;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SClass;
import som.vmobjects.SSymbol;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private VM vm;
  private Map<SSymbol, ClassFactory> factories;
  private Map<Long, RootNode> rootNodes;

  /*
   * map keeps track of revived SObject
   * This is necessary as we want to preserve the object state
   * Two objects pointing to the same third object should again point to the same object
   */
  private Map<Object, SAbstractObject> revivedObjects;
  private Map<SSymbol, SClass> revivedClasses;
  public Actor absorbingActor;

  public TimeTravellingDebugger(final VM vm) {
    this.vm = vm;
    factories = new HashMap<SSymbol, ClassFactory>();
    rootNodes = new HashMap<Long, RootNode>();
    revivedObjects = new HashMap<Object, SAbstractObject>();
    revivedClasses = new HashMap<SSymbol, SClass>();
    absorbingActor = Actor.createActor(vm);
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
  public void replayMethod(final SSymbol messageName, final SAbstractObject target, final Object[] arguments) {
    Dispatchable method = target.getSOMClass().lookupMessage(messageName, AccessModifier.PUBLIC);
    method.invoke(IndirectCallNode.create(), arguments);
  }

  public void replayMessage(final EventualMessage msg) {
    msg.execute();
  }
}
