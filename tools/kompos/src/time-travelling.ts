import { TimeTravelResponse, TimeTravelFrame } from "./messages";
import { Controller } from "./controller";
import { VmConnection } from "./vm-connection";
import { ProcessView } from "./process-view";
import { View } from "./view"
import { dbgLog } from "./source";
import { Activity } from "./execution-data";

export class TimeTravellingDebugger {
	private timeTravelMode: boolean;
	private controller: Controller;

	private frames: TimeTravelFrame[];
	private current: number;
	
	timeTravel(activityId: number, messageId: number) {
		if(!this.timeTravelMode) {
			ctrl.switchBehaviour(new TimeTravelBehaviour(this));
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
			const frame = this.frames[this.current];
			this.controller.onStackTrace(frame.stack);
			this.controller.onScopes(frame.scope)
			for(let variable of frame.variables) {
				for(let v of variable.variables) {
					dbgLog("variable: " + variable.variablesReference + " " + v.name + " " + v.value);
				}
				this.controller.onVariables(variable);
			}
		} else {
			dbgLog("out of frames")
		}
		
	}

	public step(_act: Activity, step: string) {
		if(step == "stepOver") {
			this.current++;
			this.displayTimeTravelFrame();
		}
		dbgLog("called step: " + step);
	}

	constructor(controller: Controller) {
		this.controller = controller;
		this.timeTravelMode = false;
		ProcessView.timeDbg = this;
  }
}


export abstract class ControllerBehaviour {
	public abstract requestScope(number): void;
	public abstract requestVariables(number): void;
	public abstract step(Activity, string): void;
}

export class DefaultBehaviour extends ControllerBehaviour {
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
    
    if (act.running) { dbgLog("return from step"); return; }
    act.running = true;
    this.vmConnection.sendDebuggerAction(step, act);
    this.view.onContinueExecution(act);
  }
}

export class TimeTravelBehaviour extends ControllerBehaviour {
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