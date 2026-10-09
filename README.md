# intelli-wayside-reader

Trackside railway reader for **Charkop**, on port **8082**. A train passes. Its two RFID tags (one at
each end) identify it. The wheel sensors, read by the board's SAMD21, give axle count, direction and
speed. The reader makes **one cloud POST per train**.

Design, and every value that still has to be measured on site: `docs/Wayside-Reader-Design.md` in
the workspace repo (`intelli-rfid-reader`).

## Wheel 1, Wheel 2 and direction (2026-10-09)

**J23 is Wheel 1 and J22 is Wheel 2** (board silk). **Wheel 1 first = `UP`, Wheel 2 first = `DOWN`**,
fixed in `AxleBuilder` and not configurable. In each RSR110d the element on pin 4 must be on the
Wheel 1 side, or every pass reads `UNKNOWN`. Two distances, measured on site:
`wayside.wheel.element-spacing-m` (between one sensor's two elements, one value for both; gives
`speedAtWheel1Kmh` / `speedAtWheel2Kmh` per axle) and `wayside.wheel.sensor-spacing-m` (Wheel 1 to
Wheel 2; 0 = the sum of `wheel1-to-wpms-m` + `wpms-length-m` + `wpms-to-wheel2-m`). The old names
`system-spacing-m` / `head-spacing-m` still bind. Packaged starting values, to fine-tune on site:
element spacing 0.06 m, Wheel 1 → WPMS 13 m, WPMS 3.5 m, WPMS → Wheel 2 18.5 m. The pass JSON keeps
`headA`/`atA` = Wheel 2 and `headB`/`atB` = Wheel 1.

## State (2026-09-29)

Phases 1 and 2 of the design's build plan are done:

- **Pass logic**: `PassTracker`, a state machine (IDLE → OCCUPIED → TAIL). `AxleBuilder` pairs
  each sensor's two elements into axles, then derives direction (UP/DOWN/MIXED/UNKNOWN, never a
  majority vote), speed, and the per-sensor axle counts.
- **Wheel link**: protocol v1 (COBS + CRC-16/CCITT-FALSE, little-endian), `TickClock` (unwrap plus a
  linear tick→host fit), `SerialWheelSource` on `/dev/ttyAMA3` (configured with `stty`, no serial
  library), and `SimulatedWheelSource`, which plays trains as real encoded frames.
- **Degraded mode**: with the wheel link down, a pass opens on the first tag and closes on
  `tag-gap-ms` of silence. Identity is still delivered; axles, direction and speed are null.
- **Cloud**: `CloudSender`, with Bearer auth, retry on 5xx, no retry on 4xx, a spool, replay, and a
  7-day dead-letter. **No endpoint is defined yet**, so `wayside.cloud.url` is empty and passes go to
  the local history only.
- **Local API**: `/api/v1/status`, `/passes/latest`, `/passes?since=`, `/passes/{id}`,
  `/wheel/levels`, and `/events` (SSE). All of them are ADMIN through core's default-deny
  `ScopeRules`.
- **Bench**: `POST /api/bench/train` plays a simulated train. It needs `wayside.bench.enabled` and
  `wayside.wheel.source: SIMULATED`.

**Not built, and why:**

- The SAMD21 firmware (phase 3, its own repo). With none, the serial wheel link stays DOWN.
- `POST /api/v1/wheel/capture`, which needs firmware.
- The detection thresholds are zero, and zero refuses to start the serial link. The Frauscher
  datasheet sets them.
- Train-id decoding runs `RAW` until the tag encoding is known, so `complete` is always false.

## J26 IN1/IN2 trigger, and the tag page (2026-09-29, on `intellisbc2`)

- **`wayside.trigger.source: GPIO`**: whichever of J26 IN1 and IN2 fires first starts a train and
  sets `direction` (IN1 first = `UP`, `in1-is-up` flips it). The other input ends the train. Tested
  live both ways, and both passes were delivered to the cloud with 200.
- **`/tags.html`**: Read lists every tag in the field with its TID (read only) and its EPC
  (editable). Write puts a new EPC on the tag with that TID and reads it back. The API is
  `GET /api/v1/tags/scan` and `POST /api/v1/tags/epc {tid, epc}`, and needs the COMMISSION scope.
  It runs in Gen2 session 0 and restores the configured session afterwards; in session 1, half the
  TID reads failed. It is refused while a train is passing.

## Build and test

```bash
cd ../intelli-rfid-core && mvn -o install      # if core changed
mvn -o package                                  # 29 tests, no hardware needed
```

End to end with no module and no firmware (a simulated SAMD21 and an unopened reader):

```bash
java -jar target/intelli-wayside-reader-1.0.0-SNAPSHOT.jar --server.port=18082 \
  --rfid.security.enabled=false --rfid.reader.auto-start=false \
  --wayside.wheel.source=SIMULATED --wayside.bench.enabled=true \
  --wayside.wheel.sensor-spacing-m=20 --wayside.wheel.element-spacing-m=0.14 \
  --wayside.pass.axle-gap-ms=3000 --wayside.spool-dir=/tmp/wayside-spool \
  --wayside.cloud.spool-file=/tmp/wayside-cloud.jsonl \
  --wayside.cloud.dead-letter-file=/tmp/wayside-dead.jsonl \
  --rfid.gs1.serial-counter-file=/tmp/wayside-serial --logging.file.name=/tmp/wayside.log
curl -X POST -H 'Content-Type: application/json' \
     -d '{"direction":"DOWN","speedKmh":45,"cars":2}' localhost:18082/api/bench/train
sleep 12; curl localhost:18082/api/v1/passes/latest
```

Measured 2026-09-29 on `intellisbc2`: 8/8 axles at both heads, `DOWN`, 45.0 km/h, both tags,
`CLEARED`. The result was POSTed to a mock cloud with the Bearer token.

## Deploy (wayside boards only)

**Deployed 2026-09-29 on `intellisbc2`**, the wayside development board (its tunnel is disabled). It
came up healthy: the module opened on fw 20.26.08.19 under `RG_IN`, and the triggered carrier dropped
0.8 s after connect. The wheel link is UP to the SAMD21 running `intelli-wayside-reader-mcu`, with
all four loops OPEN (no sensors fitted). Requests without a key get 401.

**It cannot run beside the tunnel.** Both apps own `/dev/ttyAMA0` and the JNI library. The unit
declares `Conflicts=intelli-rfid-tunnel.service`, and `redeploy.sh` refuses to run on a board whose
tunnel is enabled.

```bash
sudo deploy/install.sh                 # installs, does not enable
sudo install -m 640 -g intelli-sbc deploy/site-config.example.yml \
     /etc/intelli/intelli-wayside-reader/application.yml   # then edit it
sudo systemctl enable --now intelli-wayside-reader
deploy/redeploy.sh                     # thereafter, as intelli-sbc
```

Per board:

- **The antenna port.** The unit selects J25/ANT2. Sweep both ports with an antenna fitted on each
  and choose the good one.
- **The region.** Use `RG_IN` only on fw `20260819` with auth `INDIA`.
- **`dtoverlay=uart3`**, for the wheel link.
