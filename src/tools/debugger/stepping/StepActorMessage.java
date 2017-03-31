package tools.debugger.stepping;

import org.java_websocket.WebSocket;

import com.oracle.truffle.api.debug.SuspendedEvent;
import com.oracle.truffle.api.source.SourceSection;

import tools.SourceCoordinate;
import tools.SourceCoordinate.FullSourceCoordinate;
import tools.debugger.FrontendConnector;
import tools.debugger.frontend.Suspension;
import tools.debugger.message.Message.IncommingMessage;
import tools.debugger.stepping.Stepping.SteppingType;
/**
 *
 * Represents a message received from the front-end
 * for perform stepping operations in actor messages.
 *
 */
public abstract class StepActorMessage extends IncommingMessage{
  private final long activityId;

  /**
   * Note: meant for serialization.
   */
  protected StepActorMessage() {
    activityId = -1;
  }

  @Override
  public void process(final FrontendConnector connector, final WebSocket conn) {
    Suspension susp = connector.getSuspension(activityId);
    assert susp.getEvent() != null : "didn't find SuspendEvent";
    StepActorOperation stepOperation = processStepping(susp.getEvent());
    connector.registerOrUpdate(stepOperation);
    susp.resume();
  }

  public abstract StepActorOperation processStepping(SuspendedEvent event);

  public static class StepIntoMessage extends StepActorMessage {

    @Override
    public StepActorOperation processStepping(final SuspendedEvent event) {
      SourceSection source = event.getSourceSection();
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      return new StepActorOperation(sourceCoord, SteppingType.STEP_INTO);
    }
  }

  public static class StepOverMessage extends StepActorMessage {
    @Override
    public StepActorOperation processStepping(final SuspendedEvent event) {
      SourceSection source = event.getSourceSection();
      FullSourceCoordinate sourceCoord = SourceCoordinate.create(source);
      return new StepActorOperation(sourceCoord, SteppingType.STEP_OVER);
    }
  }
}
