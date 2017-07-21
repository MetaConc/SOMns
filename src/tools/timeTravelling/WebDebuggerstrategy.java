package tools.timeTravelling;

import tools.debugger.frontend.Suspension;

public interface WebDebuggerstrategy {
  public void sendStoppedMessage(final Suspension suspension);
}
