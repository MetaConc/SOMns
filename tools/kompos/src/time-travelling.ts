import { TimeTravelResponse, TimeTravelFrame } from "./messages";
import { Controller } from "./controller";
import { VmConnection } from "./vm-connection";
import { ProcessView } from "./process-view";
import { dbgLog } from "./source";

export class TimeTravellingDebugger {
	private timeTravelMode: boolean;
	private controller: Controller;
	private frames: TimeTravelFrame[];
	
	timeTravel(activityId: number, messageId: number) {
		if(!this.timeTravelMode) {
			ctrl.switchBehaviour(new TimeTravelBehaviour(this));
		}
		ctrl.timeTravel(activityId, messageId);
	}

	public onTimeTravelResponse(msg: TimeTravelResponse) {
		dbgLog("success");
		this.frames = msg.frames;
		const timeTravelFrame = this.frames[0];

		this.controller.onStackTrace(timeTravelFrame.stack);
		this.controller.onScopes(timeTravelFrame.scope);
		for(let variable of timeTravelFrame.variables) {
			this.controller.onVariables(variable);
		}
	}

	constructor(controller: Controller) {
		this.controller = controller;
		this.timeTravelMode = false;
		ProcessView.timeDbg = this;
  }
}


export abstract class ControllerBehaviour {
	public abstract requestScope(number): void;
	public abstract requestVariables(number) : void;
}

export class DefaultBehaviour extends ControllerBehaviour {
	private vmConnection: VmConnection;

	constructor(vmConnection: VmConnection) {
		super();
		this.vmConnection = vmConnection;
	}

	requestScope(topFrameId: number) {
		this.vmConnection.requestScope(topFrameId);
	}

	requestVariables(variablesReference: number){
		this.vmConnection.requestVariables(variablesReference);
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
}