package tools.timeTravelling;

import org.java_websocket.WebSocket;

import som.vm.VmSettings;
import tools.debugger.FrontendConnector;
import tools.debugger.message.Message.IncommingMessage;

// sould be moved to tools.debugger.message in main branch
public class TimeTravelMessage extends IncommingMessage {
  private long actorId;
  private long messageId;

  public TimeTravelMessage(final long actorId, final long messageId) {

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
    VmSettings.switchToTimeTravel();
    Database.prepareForTimeTravel(actorId, messageId);
  }
}
