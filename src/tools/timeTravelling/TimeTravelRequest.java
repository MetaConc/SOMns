package tools.timeTravelling;

import org.java_websocket.WebSocket;

import som.VM;
import tools.debugger.FrontendConnector;
import tools.debugger.message.Message.IncommingMessage;

// sould be moved to tools.debugger.message in main branch
public class TimeTravelRequest extends IncommingMessage {
  private long actorId;
  private long messageId;

  public TimeTravelRequest(final long actorId, final long messageId) {

    this.actorId = actorId;
    this.messageId = messageId;
  };

  public long getActorId() {
    return actorId;
  }

  public long getMessageId() {
    return messageId;
  }

  @Override
  public void process(final FrontendConnector connector, final WebSocket conn) {
    VM.getTimeTravellingDebugger().prepareForTimeTravel();
    Database.timeTravel(actorId, messageId);
  }
}
