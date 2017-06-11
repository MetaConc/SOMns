package tools.timeTravelling;

import org.java_websocket.WebSocket;

import tools.debugger.FrontendConnector;
import tools.debugger.message.Message.IncommingMessage;

// sould be moved to tools.debugger.message in main branch
public class TimeTravelMessage extends IncommingMessage {
  private long actorId;
  private long messageId;
  private boolean full;

  public TimeTravelMessage(final long actorId, final long messageId, final boolean full) {

    this.actorId = actorId;
    this.messageId = messageId;
    this.full = full;
  };



  public long getActorId() {
    return actorId;
  }

  public long getMessageId() {
    return messageId;
  }

  public boolean isFullReplay() {
    return full;
  }

  @Override
  public void process(final FrontendConnector connector, final WebSocket conn) {
    ObjectReader.readMessage(actorId, messageId);
  }
}
