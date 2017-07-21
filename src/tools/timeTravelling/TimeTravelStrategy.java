package tools.timeTravelling;

import tools.debugger.frontend.Suspension;


public class TimeTravelStrategy implements WebDebuggerstrategy {
  TimeTravellingDebugger timeTravellingDebugger;

  public TimeTravelStrategy(final TimeTravellingDebugger debugger) {
    this.timeTravellingDebugger = debugger;
  }

  @Override
  public void sendStoppedMessage(final Suspension suspension) {
    timeTravellingDebugger.sendStoppedMessage(suspension);
  }
}
