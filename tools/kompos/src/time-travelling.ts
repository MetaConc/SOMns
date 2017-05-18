import {dbgLog} from "./source"
import {Activity} from "./messages";
import {messageEvent} from "./protocol";

export class timeTravelling {
	static sessionId: number;

	static setSessionId(sessionId: number){
		timeTravelling.sessionId = sessionId;
		dbgLog("sessionId: " + sessionId);
	}

	static minimalReplay(activity: Activity, message: messageEvent){
		const actId = activity.id;
		const mesId = message.id;
		
		ctrl.tctrl.timeTravel(actId, mesId, timeTravelling.sessionId, false);
		
	}

	static fullReplay(activity: Activity, message: messageEvent){
		ctrl.timeTravel(activity.id, message.id, timeTravelling.sessionId, true);
	}
}