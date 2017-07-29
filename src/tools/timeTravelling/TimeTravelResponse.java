package tools.timeTravelling;

import tools.debugger.message.Message.OutgoingMessage;
import tools.debugger.message.ScopesResponse;
import tools.debugger.message.StackTraceResponse;
import tools.debugger.message.VariablesResponse;


public class TimeTravelResponse extends OutgoingMessage {
  private TimeTravelFrame[] frames;

  public TimeTravelResponse(final TimeTravelFrame[] frames) {
    super();
    this.frames = frames;
  }

  static class TimeTravelFrame {
    private StackTraceResponse stack;
    private ScopesResponse scope;
    private VariablesResponse[] variables;

    public TimeTravelFrame(final StackTraceResponse trace, final ScopesResponse scope, final VariablesResponse[] variables){
      this.stack = trace;
      this.scope = scope;
      this.variables = variables;
    }
  }
}

