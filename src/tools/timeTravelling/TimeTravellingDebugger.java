package tools.timeTravelling;

import java.util.HashMap;
import java.util.Map;

import som.interpreter.actors.Actor;
import som.interpreter.objectstorage.ClassFactory;
import som.vmobjects.SSymbol;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private Map<SSymbol, ClassFactory> factories = new HashMap<SSymbol, ClassFactory>();
  private Actor timeTravelActor = Actor.createActor();

  public void reportClassFactory(final ClassFactory factory) {
    this.factories.put(factory.getClassName(), factory);
  }

  public ClassFactory getFactory(final SSymbol name) {
    return this.factories.get(name);
  }

  public static void replay(final SSymbol messageName, final Object[] arguments) {
    // TODO Auto-generated method stub
    System.out.print(messageName + ": ");
    for(Object argument : arguments) {
      System.out.print(argument);
    }
    System.out.println();
  }
}
