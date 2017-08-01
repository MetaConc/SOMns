import { TimeTravelResponse, TimeTravelFrame } from "./messages";
import { UiController } from "./ui-controller";
import { VmConnection } from "./vm-connection";
import { View } from "./view"
import { dbgLog } from "./source";
import { Activity } from "./execution-data";
import { TurnNode, Message , ProcessView} from "./process-view";

export class TimeTravellingDebugger {
	private timeTravelMode: boolean;
	private controller: UiController;

	private frames: TimeTravelFrame[];
	private frameIdx: number;
	private current: TurnNode;
	
	timeTravel(node: TurnNode) {
		if(!this.timeTravelMode) {
			this.timeTravelMode = true;
			ctrl.switchBehaviour(new TimeTravelStrategy(this));
		} else { // we came from another node, remove the highlighting from that activity
			this.controller.resumeActivity(this.current.actor.activity);
		}
		this.current = node;
		ctrl.timeTravel(node.actor.getActivityId(), node.getId());
	}

	public onTimeTravelResponse(msg: TimeTravelResponse) {
		this.frames = msg.frames;
		this.frameIdx = 0;
		this.displayTimeTravelFrame();		
	}

	/*
	 * Pauses activity, displays stack, displays scopes, displays variables
	 */
	private displayTimeTravelFrame() {
		this.controller.onTimeTravelFrame(this.frames[this.frameIdx]);
	}

	private illegalStep(){
		dbgLog("called illegal step");
		this.displayTimeTravelFrame();		
	}

	public step(_act: Activity, step: string) {
		dbgLog("called step: " + step);
		switch(step) { 
			case "stepOver": { 
				this.frameIdx++;
				if(this.frameIdx<this.frames.length-1){
					this.displayTimeTravelFrame();
				} else {
					this.frameIdx--;
					this.illegalStep();
				}
				break; 
			} 
			case "stepBack": { 
				this.frameIdx--;
				if(this.frameIdx>=0){
					this.displayTimeTravelFrame();
				} else {
					this.frameIdx++;
					this.illegalStep();
				} 
				break; 
			}
			case "stepToNextTurn": {
				const node = this.current.next;
				if(node != null) {
					ProcessView.changeHighlight(node);
					this.timeTravel(node);
				} else {
					this.illegalStep();
				} 
				break;
			}
			case "stepToPreviousTurn": {
				const node = this.current.previous;
				if(node != null) {
					ProcessView.changeHighlight(node);
					this.timeTravel(node);
				} else {
					this.illegalStep();
				}
				break;
			}
			case "stepToSender": {
				const msg = this.current.incoming;
				// if message is not the empty message we can step to sender
				if(msg instanceof Message) {
					const node = (<Message> msg).sender;
					ProcessView.changeHighlight(node);
					this.timeTravel(node);
				} else {
					this.illegalStep();
				}
				break;
			} 
			default: { 
				this.illegalStep();
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