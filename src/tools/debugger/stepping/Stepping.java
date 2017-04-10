package tools.debugger.stepping;

import tools.SourceCoordinate.FullSourceCoordinate;
/**
 *
 * Manage the actor message stepping.
 *
 */
public class Stepping {

  public enum SteppingType{
    STEP_INTO,
    STEP_OVER,
    STEP_RETURN
  }

  private StepActorOperation stepActor;

  public Stepping(final StepActorOperation stepActor) {
    this.stepActor = stepActor;
  }
  /**
   * Return the stepping operation that has been made for a specific source section,
   * based on the start line of the source section.
   * It can be only compared the start line because in the case that a sequential stepping operation
   * was made before a message stepping operation, the source section fields of column and char length
   * does not correspond to the asynchronous message operator source section.
   */
  public SteppingType getSteppingTypeOperation(final FullSourceCoordinate source) {

    if (stepActor != null && source.startLine == stepActor.getSourceSection().startLine) {
      if (stepActor.getSteppingType() == SteppingType.STEP_INTO) {
        return SteppingType.STEP_INTO;
      } else if (stepActor.getSteppingType() == SteppingType.STEP_OVER) {
        return SteppingType.STEP_OVER;
      } else if (stepActor.getSteppingType() == SteppingType.STEP_RETURN) {
        return SteppingType.STEP_RETURN;
      }
    }
    return null;
  }
}
