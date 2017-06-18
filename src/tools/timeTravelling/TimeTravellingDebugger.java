package tools.timeTravelling;

import java.util.HashMap;
import java.util.Map;

import com.oracle.truffle.api.nodes.IndirectCallNode;

import som.compiler.AccessModifier;
import som.interpreter.actors.Actor;
import som.interpreter.nodes.dispatch.Dispatchable;
import som.interpreter.objectstorage.ClassFactory;
import som.vmobjects.SAbstractObject;
import som.vmobjects.SClass;
import som.vmobjects.SInvokable;
import som.vmobjects.SSymbol;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private Map<SSymbol, ClassFactory> factories = new HashMap<SSymbol, ClassFactory>();

  /*
   * map keeps track of revived SObject
   * This is necessary as we want to preserve the object state
   * Two objects pointing to the same third object should again point to the same object
   */
    private static Map<Object, SAbstractObject> revivedObjects = new HashMap<Object, SAbstractObject>();
    private static Map<SSymbol, SClass> revivedClasses = new HashMap<SSymbol, SClass>();
    private static Map<Long, Actor> revivedActors = new HashMap<Long, Actor>();


  /*
   *  Runtime information kept to make serialization easier
   */
  public void reportClassFactory(final ClassFactory factory) {
    this.factories.put(factory.getClassName(), factory);
  }

  public ClassFactory getFactory(final SSymbol name) {
    return this.factories.get(name);
  }

  /*
   *  These methods are used to cache and lookup previously revived objects.
   */
  public static synchronized void reportSAbstractObject(final Object dbRef, final SAbstractObject object) {
    revivedObjects.put(dbRef, object);
  }
  public static synchronized SAbstractObject getSAbstractObject(final Object dbRef) {
    return revivedObjects.get(dbRef);
  }


  public static SClass getSClass(final SSymbol factoryName) {
    return revivedClasses.get(factoryName);
  }
  public static void reportSClass(final SSymbol factoryName, final SClass revivedClass) {
    revivedClasses.put(factoryName, revivedClass);
  }


  public static Actor getActor(final Long actorId) {
    return revivedActors.get(actorId);
  }
  public static void reportActor(final Long actorId, final Actor revivedActor) {
    revivedActors.put(actorId, revivedActor);
  }

   /*
    * actual methods to perform replay, once the system state is restored
    */
  public static void replayMethod(final SSymbol messageName, final SAbstractObject target, final Object[] arguments) {
    Dispatchable method = target.getSOMClass().lookupMessage(messageName, AccessModifier.PUBLIC);
    method.invoke(IndirectCallNode.create(), arguments);
  }

  public static void replayFactory(final SSymbol messageName, final SClass target, final Object[] arguments) {
    SInvokable method = target.getMixinDefinition().getFactoryMethods().get(messageName);
    method.invoke(IndirectCallNode.create(), arguments);
  }
}
