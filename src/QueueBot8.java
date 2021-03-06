import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

import hlt.Constants;
import hlt.DockMove;
import hlt.GameMap;
import hlt.Log;
import hlt.Move;
import hlt.Navigation;
import hlt.Networking;
import hlt.Planet;
import hlt.Position;
import hlt.Ship;
import hlt.ThrustMove;

public class QueueBot8 {
	
	static class TargetPriority implements Comparable<TargetPriority> {
		/**
		 * @param target
		 * @param priority
		 */
		public TargetPriority(Position target, Double priority) {
			super();
			this.target = target;
			this.priority = priority;
		}

		Position target;
		Double priority;

		@Override
		public int compareTo(TargetPriority o) {
			return Double.compare(this.priority, o.priority);
		}
	}

	static int numTurnsUntilFullyDocked(Planet p) {
		int cp = p.getCurrentProduction();
		int spots = p.getDockingSpots();
		int ships = p.getDockedShips().size();
		
		if (ships <= 0) return -1;
		int nTurns = 0;
		if (ships >= spots) {
			return nTurns;
		}
		if (cp == 0) { // correction for if the ships aren't fully docked
			if (ships == 1) nTurns += 2;
			else if (ships == 2) nTurns += 1;
		}
		while (ships < spots) {
			double rp = Math.max(Constants.SHIP_COST - cp, 0);
			int newTurns = 0;
			if (ships == 1) {
				newTurns = (int) Math.ceil(rp / ((double) Constants.BASE_PRODUCTIVITY));				
			} else if (ships == 2) {
				newTurns = Math.max(1, (int) Math.ceil(rp / (1.5 * Constants.BASE_PRODUCTIVITY)));
			} else if (ships >= 3) {
				newTurns = Math.max(1, (int) Math.ceil(rp / ((ships - 1) * Constants.BASE_PRODUCTIVITY)));
			}
			nTurns += newTurns;
			cp = Math.max(0, cp + nTurns*Constants.BASE_PRODUCTIVITY - Constants.SHIP_COST);
			ships++;
		}
		return nTurns;
	}
	
    public static void main(final String[] args) {
        final Networking networking = new Networking();
        final GameMap gameMap = networking.initialize("QueueBot8");

        // We now have 1 full minute to analyze the initial map.
        final String initialMapIntelligence =
                "width: " + gameMap.getWidth() +
                "; height: " + gameMap.getHeight() +
                "; players: " + gameMap.getAllPlayers().size() +
                "; planets: " + gameMap.getAllPlanets().size();
        Log.log(initialMapIntelligence);
        
        double gameProgressParam = 0;

        final ArrayList<Move> moveList = new ArrayList<>();
        for (;;) {
            moveList.clear();
            networking.updateMap(gameMap);
            
            gameProgressParam = 0.8 + 3.0/(1.0 + Math.exp(-0.05*(gameMap.getAllShips().size() - 100)));
            
            PriorityQueue<TargetPriority> queue = new PriorityQueue<>();
            PriorityQueue<TargetPriority> microQueue = new PriorityQueue<>();
            
            HashMap<Integer, Map<Double, Ship>> plHostiles = new HashMap<>(); 
            HashMap<Integer, Map<Double, Ship>> plUdHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplUdHostiles = new HashMap<>();
            HashMap<Integer, Integer> numplDHostiles = new HashMap<>();
            for (final Planet planet : gameMap.getAllPlanets().values()) {
            	Map<Double, Ship> aMap = gameMap.hostilesNearPlanet(planet);
            	Map<Double, Ship> bMap = gameMap.undockedHostilesNearPlanet(planet);
            	
            	plHostiles.put(planet.getId(), aMap);
            	numplHostiles.put(planet.getId(), aMap.size());
            	plUdHostiles.put(planet.getId(), bMap);
            	numplUdHostiles.put(planet.getId(), bMap.size());
            	numplDHostiles.put(planet.getId(), aMap.size() - bMap.size());
//            	if (planet.getOwner() == gameMap.getMyPlayerId()) {
//            		Log.log(planet.toString());
//            	}
            }

            for (final Ship ship : gameMap.getMyPlayer().getShips().values()) {
                if (ship.getDockingStatus() != Ship.DockingStatus.Undocked) {
                    continue;
                }
                
                queue.clear();
                boolean microNearPlanet = false;
                planetLoop:
                for (final Planet planet : gameMap.getAllPlanets().values()) {
                    if (ship.withinDockingRange(planet)) {
                    	
                    	// check if there are hostiles near the planet that we need to fight
                    	HashMap<Double, Ship> hostilesNearPlanet = new HashMap<Double, Ship>(plHostiles.get(planet.getId()));
                    	
                    	// fight hostiles if necessary
                    	if (hostilesNearPlanet.size() > 0) {
                    		microQueue.clear();
                    		for (Ship hostile : hostilesNearPlanet.values()) {
                    			microQueue.add(new TargetPriority(hostile, ship.getDistanceTo(hostile)));
                    		}
                    		TargetPriority t;
                        	while ((t = microQueue.poll()) != null) {
        	                	//final ThrustMove newThrustMove = Navigation.navigateShipToDock(gameMap, ship, (Planet) t.target, Constants.MAX_SPEED*6/7);
        	                	if (t.priority < Constants.WEAPON_RADIUS) {
        	                		microNearPlanet = true;
        	                		break planetLoop;
        	                	}
                        		
                        		final ThrustMove newThrustMove =
        	                			Navigation.navigateShipToHostileShipNearPlanet(gameMap, ship,
        	                					(Ship) t.target, planet, Constants.MAX_SPEED);
        	                	if (newThrustMove != null) {
        	                		moveList.add(newThrustMove);
        	                		microNearPlanet = true;
        	                		break planetLoop;
        	                	}
                        	}
                    	} else if (!planet.isFull()) { // dock if planet is not full
	                    	microNearPlanet = true;
	                        moveList.add(new DockMove(ship, planet));
	                        break;
                    	}
                    }

                    // pick a good planet to go to
                    double dist = planet.getDistanceTo(ship) - planet.getRadius();
                    double cost = 0;
                    int numUdHostiles = numplUdHostiles.get(planet.getId());
                    int numDHostiles = numplDHostiles.get(planet.getId());
                    if (planet.getOwner() == gameMap.getMyPlayerId() && numTurnsUntilFullyDocked(planet) < dist/Constants.MAX_SPEED) {
                    	Map<Double, Ship> nh = plHostiles.get(planet.getId()); 
                    	if (!nh.isEmpty()) {  // to defend
                    		cost = dist * 100 / Math.sqrt(nh.size());
                    	} else {
                    		cost = dist * 60;
                    	}
                    } else {
                    	cost = dist * (((double) numUdHostiles)*0.3 + ((double) numDHostiles)*0.2 + 0.4);
                    }
                    cost *= Math.sqrt(gameProgressParam + planet.getDockingSpots());
                    queue.add(new TargetPriority(planet, cost));
                }
                
                if (!microNearPlanet) {
                	// navigate to planet with highest order in queue that is possible
                	TargetPriority t;
//                	Log.log(ship.getId() + " " + queue.size());
                	while ((t = queue.poll()) != null) {
	                	final ThrustMove newThrustMove = Navigation.navShipToDock_v2(gameMap, ship,
	                			(Planet) t.target, Constants.MAX_SPEED);
	                	if (newThrustMove != null) {
	                		moveList.add(newThrustMove);
	                		break;
	                	}
                	}
                }
            }
            Networking.sendMoves(moveList);
        }
    }
}
