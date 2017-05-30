package tools.timeTravelling;

import java.util.HashMap;
import java.util.Map;

import som.interpreter.objectstorage.ClassFactory;

// TODO merge file with debugger after thesis is done. This allows me to separate my work from SOM
public class TimeTravellingDebugger {
  private Map<String, ClassFactory> factories = new HashMap<String, ClassFactory>();

  public void reportClassFactory(final ClassFactory factory) {
    this.factories.put(factory.getClassName().getString(), factory);
  }

  public ClassFactory getFactory(final String name) {
    return this.factories.get(name);
  }
}
