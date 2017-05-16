import {dbgLog} from "./source"

export class timeTravelling {
	static sessionId: number;

	static setSessionId(sessionId: number){
		timeTravelling.sessionId = sessionId;
		dbgLog("sessionId: " + sessionId);
	}
}