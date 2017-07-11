package tools.timeTravelling;

import som.interpreter.actors.SPromise.SResolver;

// Dummy class to serve as promise of any time travel replay method
public class AbsorbingSResolver extends SResolver {
  protected AbsorbingSResolver() {
    super(null);
  }
}
