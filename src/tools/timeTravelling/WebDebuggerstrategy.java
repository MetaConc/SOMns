package tools.timeTravelling;

import tools.debugger.frontend.Suspension;

public interface WebDebuggerstrategy {
  void sendStoppedMessage(Suspension suspension);
}
