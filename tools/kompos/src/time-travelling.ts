import { TimeTravelResponse, TimeTravelFrame } from "./messages";
import { UiController } from "./ui-controller";
import { VmConnection } from "./vm-connection";
import { ProcessView } from "./process-view";
import { View } from "./view"
import { dbgLog } from "./source";
import { Activity } from "./execution-data";

export class TimeTravellingDebugger {
	private timeTravelMode: boolean;
	private controller: UiController;

	private frames: TimeTravelFrame[];
	private current: number;
	
	timeTravel(activityId: number, messageId: number) {
		if(!this.timeTravelMode) {
			ctrl.switchBehaviour(new TimeTravelStrategy(this));
		}
		ctrl.timeTravel(activityId, messageId);
	}

	public onTimeTravelResponse(msg: TimeTravelResponse) {
		dbgLog("success");
		this.frames = msg.frames;
		this.current = 0;
		this.displayTimeTravelFrame();		
	}

	private displayTimeTravelFrame() {
		if(this.current < this.frames.length){
			this.controller.onTimeTravelFrame(this.frames[this.current]);
		} else {
			dbgLog("out of frames")
		}	
	}

	public step(_act: Activity, step: string) {
		dbgLog("called step: " + step);
		switch(step) { 
			case "stepOver": { 
				this.current++;
				this.displayTimeTravelFrame();
				break; 
			} 
			case "stepBack": { 
				this.current--;
				this.displayTimeTravelFrame();
				break; 
			} 
			default: { 
				//statements; 
				break; 
			} 
		} 
	}

	constructor(controller: UiController) {
		this.controller = controller;
		this.timeTravelMode = false;
		ProcessView.timeDbg = this;
  }
}


export abstract class ControllerStrategy {
	public abstract requestScope(number): void;
	public abstract requestVariables(number): void;
	public abstract step(Activity, string): void;
}

export class DefaultStrategy extends ControllerStrategy {
	private vmConnection: VmConnection;
	private view: View

	constructor(vmConnection: VmConnection, view: View) {
		super();
		this.vmConnection = vmConnection;
		this.view = view;
	}

	requestScope(topFrameId: number) {
		this.vmConnection.requestScope(topFrameId);
	}

	requestVariables(variablesReference: number){
		this.vmConnection.requestVariables(variablesReference);
	}

	
  public step(act: Activity, step: string) {   
    this.vmConnection.sendDebuggerAction(step, act);
  }
}

export class TimeTravelStrategy extends ControllerStrategy {
	private timeTravelling: TimeTravellingDebugger;

	constructor(timeTravelling: TimeTravellingDebugger) {
		super();
		this.timeTravelling = timeTravelling;
	}

	requestScope(_topFrameId: number) {}; // No need to request the scope as it is already in the frame
	requestVariables(_variablesReference: number){} // No need to request the variables they are already in the frame

	public step(act: Activity, step: string) { 
		this.timeTravelling.step(act, step);
	}
}