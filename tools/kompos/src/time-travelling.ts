import {dbgLog} from "./source"

export class timeTravelling {
	static sessionId: number;

	static setSessionId(sessionId: number){
		timeTravelling.sessionId = sessionId;
	}

	static timeTravel(activityId: number, messageId: number, full: boolean){
		dbgLog("time travel: " + activityId +" " + messageId + " "+ full);
		ctrl.timeTravel(activityId, messageId, timeTravelling.sessionId, full);
	}
}