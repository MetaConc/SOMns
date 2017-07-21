package tools.timeTravelling;

import tools.debugger.frontend.Suspension;

public interface ConnectorStrategy {
  public void sendStoppedMessage(final Suspension suspension);
}
