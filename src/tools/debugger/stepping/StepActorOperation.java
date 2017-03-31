package tools.debugger.stepping;

import tools.SourceCoordinate.FullSourceCoordinate;
import tools.debugger.stepping.Stepping.SteppingType;
/**
 *
 * Represents an stepping operation.
 *
 */
public class StepActorOperation {
  FullSourceCoordinate source;
  SteppingType type;

 public StepActorOperation(final FullSourceCoordinate source, final SteppingType type) {
   this.source = source;
   this.type = type;
 }

 public FullSourceCoordinate getSourceSection() {
   return this.source;
 }

 public SteppingType getSteppingType() {
   return type;
 }
}
