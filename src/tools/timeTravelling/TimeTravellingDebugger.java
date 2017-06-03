package tools.timeTravelling;

import java.util.HashMap;
import java.util.Map;

import som.interpreter.objectstorage.ClassFactory;
import som.vmobjects.SSymbol;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private Map<SSymbol, ClassFactory> factories = new HashMap<SSymbol, ClassFactory>();

  public void reportClassFactory(final ClassFactory factory) {
    this.factories.put(factory.getClassName(), factory);
  }

  public ClassFactory getFactory(final SSymbol name) {
    return this.factories.get(name);
  }
}
