package tools.timeTravelling;

import tools.debugger.message.Message.Response;
import tools.debugger.message.StackTraceResponse;
import tools.debugger.message.VariablesResponse;


public class TimeTravelResponse extends Response {
  private StackTraceResponse stackTrace;
  private TimeTravelFrame[] frames;

  public TimeTravelResponse(final int requestId, final StackTraceResponse stackTrace, final TimeTravelFrame[] frames) {
    super(requestId);
    this.stackTrace = stackTrace;
    this.frames = frames;
  }

  static class TimeTravelFrame {
    private VariablesResponse[] variables;

    public TimeTravelFrame(final VariablesResponse[] variables){
      this.variables = variables;
    }
  }
}

