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

  public SteppingType getSteppingTypeOperation(final FullSourceCoordinate source) {
    if (stepActor != null && source.equals(stepActor.getSourceSection())) {
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
