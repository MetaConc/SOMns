import * as d3 from "d3";
import {Activity, IdMap} from "./messages";
import {HistoryData} from "./history-data"
//import {dbgLog} from "./source";

const actorStart = 20; // height at which actor headings are created
const actorHeight = 30; // height of actor headings
const actorWidth = 60; // width of actor headings
const actorSpacing = 100;
const turnRadius = 20;
const turnSpacing = 50; // space between consequent turns
const turnWidth = 2;
const turnHighlightedWidth = 5;
const opacity = 0.5;

var svgContainer;
var color = ["#3366cc", "#dc3912", "#ff9900", "#109618", "#990099", "#0099c6", "#dd4477", "#66aa00", "#b82e2e", "#316395", "#994499", "#22aa99", "#aaaa11", "#6633cc", "#e67300", "#8b0707", "#651067", "#329262", "#5574a6", "#3b3eac"];

//actor each have their own svg groups
//one group for the heading: the square and the text field. 
//one group for all the other elements: turns and messages
//  to hide an actor the other group and all incoming messages are set to hidden
class ActorHeading {
  name:               string;
  x:                  number;
  y:                  number;
  color:              string;
  turnCount:          number;
  container:          d3.Selection<SVGElement>;
  turns:              TurnNode[];
  static actorCount:  number = 0;
  visibility:         boolean;  

  constructor(name: string) {
    this.name = name;
    this.color = color[ActorHeading.actorCount % color.length];
    this.x = 50+ActorHeading.actorCount++*actorSpacing;
    this.y = actorStart;
    this.turnCount = 0;
    this.visibility = true;
    this.turns = [];
    drawActor(this);
  }

  addTurn(turn: TurnNode){
    this.turns.push(turn);
    return ++this.turnCount;
  }

  lastTurn(){
    return this.turns[this.turns.length - 1];
  }

  changeVisibility() {
    this.visibility = !this.visibility;
    if(this.visibility){
      this.container.style("visibility", "inherit");
    } else {
      this.container.style("visibility", "hidden");
    }
    for (const turn of this.turns) {
      turn.changeVisibility(this.visibility);
    }
  }
}

class TurnNode {
  x:              number;
  y:              number;
  incoming:       EmptyMessage;
  actor:          ActorHeading;
  visualization:  d3.Selection<SVGElement>;

  constructor(actor: ActorHeading, message: EmptyMessage){
    var count = actor.addTurn(this);
    this.actor = actor;
    this.x = actor.x + (actorWidth / 2);
    this.y = actorStart + actorHeight + count * turnSpacing;
    this.incoming = message; //possible no message
    this.visualization = drawTurn(this);
  }

  highlightOn(){
    this.visualization.style("stroke-width", turnHighlightedWidth)
                      .style("stroke", "black");
    this.incoming.highlightOn();
  }

  highlightOff(){
    this.visualization.style("stroke-width", turnWidth)
                      .style("stroke", this.getColor());
    this.incoming.highlightOff();
  }

  //the turn itself is made invisible by the group, only the incoming arc needs to be explicitly made invisible
  changeVisibility(visible: boolean){
    this.incoming.changeVisibility(visible);
  }

  getColor(){
    return this.actor.color;
  }

  getText(){
    return this.incoming.getText();
  }
}

class EmptyMessage{
  constructor (){};
  highlightOn(){};
  highlightOff(){};
  changeVisibility(_visible: boolean){};
  getText(){return "42"};
}

class Message extends EmptyMessage{
  sender:        TurnNode;
  target:        TurnNode;
  text:          string; 
  visualization: d3.Selection<SVGElement>;

  constructor(senderActor: ActorHeading, targetActor: ActorHeading, text: string){
    super();
    this.text = text;
    var lastTurn = senderActor.lastTurn();
    if(lastTurn){
      this.sender = lastTurn;
    } else {
      this.sender = new TurnNode(senderActor, new EmptyMessage());
    }
    this.target = new TurnNode(targetActor, this);  
    if(senderActor === targetActor){
      this.visualization = drawMessageToSelf(this.sender, this.target);
    } else {  
      this.visualization = drawMessage(this.sender, this.target);
    }
  }

  highlightOn(){
    this.visualization.style("stroke-width", turnHighlightedWidth);
    this.sender.highlightOn();
  }

  highlightOff(){
    this.visualization.style("stroke-width", 1);
    this.sender.highlightOff();
  }

  changeVisibility(visible: boolean){
    if(visible){
      this.visualization.style("visibility", "inherit");
    } else {
      this.visualization.style("visibility", "hidden");
    }
  }

  getText(){
    return this.text;
  }
} 

export class ProtocolOverview {
  private actors: IdMap<ActorHeading>;
  private data: HistoryData;
  private static highlighted: TurnNode;

  public newActivities(newActivities: Activity[]){
    for(const act of newActivities){
      if(act.type === "Actor"){
        var actor = new ActorHeading(act.name);
        this.actors[act.id] = actor;
      }
    }
  }

  public newMessages(newMessages: [number, number, number][]){
    for(const [senderId, targetId, messageId] of newMessages){
      var senderActor = this.actors[senderId];
      var targetActor = this.actors[targetId];
      var message = this.data.getName(messageId);
      new Message(senderActor, targetActor, message);
    }
  }

  public constructor(data: HistoryData){
    displayProtocolOverview();
    ActorHeading.actorCount = 0;
    this.actors = {};
    this.data = data;
  }

  //ensure only one node chain can be highlighted at the same time
  static changeHighlight(turn: TurnNode){
    if(ProtocolOverview.highlighted){
      ProtocolOverview.highlighted.highlightOff();
    }
    if(turn == ProtocolOverview.highlighted){
      ProtocolOverview.highlighted = null;
    } else {
      turn.highlightOn();
      ProtocolOverview.highlighted = turn;
    }
  }
}

function displayProtocolOverview() {
  const canvas = $("#protocol-canvas");
  canvas.empty(); // after every restart the canvas needs to be redrawn in case a different program is running on the backend

  svgContainer = d3.select("#protocol-canvas")
    .append("svg")
    .attr("width", 1000)
    .attr("height", 1000)
    .attr("style", "background: none;");
}

function drawActor(actor: ActorHeading){
  var actorHeading = svgContainer.append("g");
  var actorGroup = svgContainer.append("g");
  actor.container = actorGroup;
  
  actorHeading.append("text")
    .attr("x", actor.x+actorWidth/2)
    .attr("y", actor.y+actorHeight/2)
    .attr("font-size","20px")
    .attr("text-anchor", "middle")
    .text(actor.name);    

  actorHeading.append("rect")
    .attr("x", actor.x)
    .attr("y", actor.y)
    .attr("rx", 5)
    .attr("height", actorHeight)
    .attr("width", actorWidth)
    .style("fill", actor.color)
    .style("stroke", actor.color)
    .style("opacity", opacity)
    .on("click", function(){
      actor.changeVisibility();
    });
}

function drawTurn(turn: TurnNode){
  var text = turn.actor.container.append("text")
    .attr("x", turn.x)
    .attr("y", turn.y)
    .attr("font-size","20px")
    .attr("text-anchor", "middle")
    .style("opacity", 0)
    .text(turn.getText());   

  var visualization = turn.actor.container.append("circle")
      .attr("cx", turn.x)
      .attr("cy", turn.y)
      .attr("r", turnRadius)
      .style("fill", turn.getColor())
      .style("opacity", opacity)
      .style("stroke-width", turnWidth)
      .style("stroke", turn.getColor())
      .on("click", function(){
        ProtocolOverview.changeHighlight(turn);
      })
      .on("mouseover", function(){
        text.style("opacity", 1);
      })
      .on("mouseout", function(){
        text.style("opacity", 0);
      });
  return visualization;
}

function drawMessage(sender: TurnNode, target: TurnNode){
  var visualization = sender.actor.container.append("line")
    .attr("x1", sender.x)
    .attr("y1", sender.y)
    .attr("x2", target.x)
    .attr("y2", target.y)
    .style("stroke", sender.getColor());
  return visualization;
}

function drawMessageToSelf(sender: TurnNode, target: TurnNode){
  var lineData: [number, number][] = [
    [ sender.x , sender.y],
    [ sender.x+turnRadius*1.5 , sender.y],
    [ target.x+turnRadius*1.5 , target.y], 
    [ target.x , target.y]];
  var lineGenerator: any = 
      d3.svg.line()
        .x(function(d) { return d[0]; })
        .y(function(d) { return d[1]; })
        .interpolate("linear");
  var visualization = sender.actor.container.append("path")
    .attr("d", lineGenerator(lineData))
    .style("fill", "none")
    .style("stroke", sender.getColor());
  return visualization;
}