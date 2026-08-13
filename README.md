# ShadowGPS

An Android navigation app that routes you **around** automated licence plate readers
(Flock Safety and friends) and other roadside surveillance, instead of straight past them.

It works like any other maps app — search a destination, pick a route, get turn-by-turn
directions with voice — except every route comes with a second number next to the ETA:
how many cameras it drives you past. You choose the trade.

```
  Fastest     12 min · 6.4 km          3 seen
  Balanced    14 min · 7.1 km          1 seen
  Discreet    18 min · 9.0 km          0 unseen     ← default
```

## What it avoids

| Kind | Default | Why |
|---|---|---|
| **Licence plate readers (ALPR)** | avoided | Records your plate, place and time, and retains it |
| Toll gantries | tracked, not avoided | Read plates by design, but usually unavoidable |
| Speed cameras | tracked, not avoided | Only record on a violation |
| Red-light cameras | tracked, not avoided | Only record on a violation |
| Traffic CCTV | tracked, not avoided | Rarely identifies a vehicle, and is everywhere |

Every category is a switch in settings. Turning one on makes the router detour around it;
turning it off leaves it drawn on the map but ignored when routing.

## How it works

Everything runs on the phone.

1. **Data.** The road network and every mapped surveillance device inside the trip's
   bounding box are downloaded from [OpenStreetMap](https://www.openstreetmap.org) via the
   Overpass API, and cached on disk. Plate readers are the `man_made=surveillance` +
   `surveillance:type=ALPR` tagging used by the community ALPR mapping projects; vendor
   tags like `manufacturer=Flock Safety` are recognised on their own.
2. **Exposure model.** For every stretch of road, `ExposureModel` works out how exposed it
   is to each nearby device — how close the road passes, whether it falls inside the
   camera's cone when a facing direction is mapped, and which way you are travelling
   through it. The result is a number from 0 (cannot see the road) to 1 (clean capture).
3. **Routing.** An edge-based A* search minimises

   ```
   travel seconds  +  λ × exposure
   ```

   where **λ is the only difference between the profiles**: how many seconds of detour you
   are willing to spend to dodge one camera. Fastest sets λ to 0. Ghost sets it to 3000.
   Because the penalty is never negative, the search stays optimal for the combined cost.
4. **Guidance.** Turn-by-turn instructions, spoken directions, automatic rerouting after a
   wrong turn, and a spoken warning before each camera you are about to pass.

**No origin or destination ever leaves the device.** There is no routing server to send
them to — that is the entire point. The only network calls are OpenStreetMap data for an
area, map tiles, and address search if you use it.

## Repository layout

```
core/     Pure Kotlin/JVM. Geometry, OSM parsing, road graph, exposure model,
          A* router, turn-by-turn, live navigation. No Android dependencies,
          fully unit-tested.
app/      Android app: Jetpack Compose UI, osmdroid map, location, voice,
          foreground service.
```

`core` is deliberately Android-free so the routing logic can be tested on a synthetic grid
city where the right answer is obvious by inspection:

```bash
./gradlew :core:test          # 73 tests, no Android SDK needed
```

## Building

Requires JDK 17+ and the Android SDK (API 35).

```bash
git clone https://github.com/EndXSuffering/shadowgps
cd shadowgps
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Open the folder in Android Studio and it will work as-is.

If no Android SDK is visible (no `ANDROID_HOME`, no `local.properties`), the `:app` module
is skipped automatically with a warning and `:core` still builds and tests. Force either
way with `-Pshadowgps.android=true|false`.

CI builds the APK and runs the tests on every push; the debug APK is attached to each run
as an artifact.

## Limitations — read this part

- **The map is only as good as OpenStreetMap.** ALPR coverage is crowd-sourced and
  incomplete. A route with "0 seen" means *no camera is mapped* along it, not that no
  camera exists. Treat it as a best effort, never as a guarantee.
- **Cameras move.** Flock units in particular are frequently relocated. Cached data is
  refreshed every few days; a device installed yesterday is not in it.
- **Facing directions are mostly unmapped**, so most devices are modelled as seeing in
  every direction — the cautious assumption, which can make routes more paranoid than they
  need to be.
- **Trip size is capped** at roughly 4000 km² of bounding box, because the road graph is
  built in phone memory. Long motorway journeys are not what this is for.
- **No live traffic**, no turn restrictions, no lane guidance. ETAs come from speed limits
  and junction counts, so treat them as estimates.
- Routing quality depends on the padding around your route (3 km by default). A detour
  that would need to swing wider than that will not be found.

## Contributing camera data

The most useful thing you can do for this app is not code — it is mapping. If you know of
a plate reader that is not on the map, add it to OpenStreetMap:

```
man_made=surveillance
surveillance:type=ALPR
surveillance=public
direction=<degrees the camera faces>
manufacturer=<e.g. Flock Safety>
```

Every app and project using this data gets better at once.

## Legal and ethical note

This app helps you choose which public roads to drive on, using a public map. Driving a
lawful route that happens to avoid a camera is not an offence, and no part of this app
obscures, alters or interferes with a plate, a camera, or any other equipment.

It is a privacy tool for ordinary people who would rather not have their movements logged
and retained by default. Please do not use it to help commit crimes — and note that it
would be poor at that anyway, given the data caveats above.

## Licence

MIT. Map data © OpenStreetMap contributors, available under the
[Open Database Licence](https://www.openstreetmap.org/copyright).
