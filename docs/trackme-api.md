> **Copy of the TrackMe usage notes.** The API secret was removed so it's safe to commit; the real value is in `local.properties` (never committed).
>
> ✅ **Checked against the live spec (`docs/openapi.json`, captured 2026-10-28).** Request paths below have been corrected to match it.
> - Only the health checks live at `{API_URL}/health` and `/health/infra`. **Everything else is under `{API_URL}/v1/…`**.
> - Login is `POST {API_URL}/v1/auth/login`. The original notes said `/health`.
> - Join Trip is **POST** (the original notes said GET). Rotate view link is **POST** (also listed as GET).
> - Not in these notes but in the spec: `GET /v1/notifications` (cursor paged), `POST /v1/notifications/{id}/read`, `POST /v1/notifications/read-all`, `GET /health/infra`.
> - Constraints from the spec: `duration_min` 5–1440; join `code` 1–16 chars; `GET /v1/trips?limit=` 1–50; history accepts optional `from` / `to`; `DELETE /v1/trips/{id}` on a live trip → 409 (end it first).
> - Auth: every `/v1` endpoint except login and trip preview needs `Authorization: Bearer <access_token>`.

### Some constants we'll use
- API Docs: https://tracker.baato.io/trackme/docs
- Variables
```dart
SOCKET_URL = 'https://tracker.baato.io/trackme'
API_URL = 'https://tracker.baato.io/trackme/api'
API_SECRET = '<see local.properties: trackme.apiSecret>'
```

- *socket.io* library is used
## Websocket Events

| Event          | Description                                                  |
| -------------- | ------------------------------------------------------------ |
| member_changed | if new user joins or any member leaves connection            |
| trip_ended     |  If trip is dismissed by owner or trip duration is completed |
| trip_position  | New position of members on this trip                         |

Full contract (auth, join events, payloads, `evicted`, `share_*`): see [Realtime (Socket.IO)](#realtime-socketio) at the end of this file.

## Implementation Workflow
#### 1. Service Health Check
- Check If service is down or not
- Request: *GET*  `{API_URL}/health`
- Response: 
```json
{
  "status": "ok"
}
```

#### 2. Login
- Login to the service then you'll get an Bearer **token** store that token and send it while accessing features below. 
- Recommended that when user creates new trip login or decode token and if expired re-login
- Request: *POST*  `{API_URL}/v1/auth/login`
- Payload: 
```json
{
  "email": "user@example.com",
  "full_name": "Example User",
  "api_secret": "your_secretr"
}
```
- Response: 
```json
{
  "access_token": "<access token (JWT)>",
  "email": "user@example.com",
  "full_name": "Example User"
}
```


#### 3. Create Trip
- Show the **join_code** below  in user interface and allow them to copy it in clipboard
- Request: *POST*  `{API_URL}/v1/trips`
- Payload: 
```json
{
  "name": "Trip 17",
  "duration_min":60
}
```
- Response: 
```json
{
  "id": 17,
  "name": "Trip 17",
  "join_code": "C7FKK7",
  "view_link": "https://tracker.baato.io/trackme/s/n_3vSntVRkdrFqfvA9TkHaDsz5Ym5JTRoDslFZr0iMQ",
  "expires_at": "2026-10-16T07:40:37.547618Z",
  "remaining_s": 3599
}
```


#### 4. View All Trips
- Created or joined trips
- Request: *GET*  `{API_URL}/v1/trips`
- Response: 
```json
{
  "trips": [
    {
      "id": 12,
      "name": "Trip 6",
      "my_role": "owner",
      "my_status": "joined",
      "status": "live",
      "ended_reason": null,
      "member_count": 2,
      "started_on": "2026-10-16T05:53:07.430550Z",
      "ended_on": null,
      "planned_s": 3600,
      "actual_s": 299,
      "expires_at": "2026-10-16T06:53:07.430550Z",
      "remaining_s": 3300,
      "join_code": "JG8QJS"
    },
    

    {
      "id": 2,
      "name": "Trip 1",
      "my_role": "owner",
      "my_status": "joined",
      "status": "ended",
      "ended_reason": "expired",
      "member_count": 1,
      "started_on": "2026-10-15T09:11:49.692035Z",
      "ended_on": "2026-10-15T09:16:49.692035Z",
      "planned_s": 300,
      "actual_s": 300,
      "expires_at": "2026-10-15T09:16:49.692035Z",
      "remaining_s": 0,
      "join_code": null
    }
  ]
}
```


#### 5. Preview Trip before joining (as trip member)
- You must have *join code* to use in UI
- Request: *GET*  `{API_URL}/v1/trips/preview?code=<join_code>`

- Response: 
```json
{
  "name": "Trip 17",
  "member_count": 1,
  "owner_name": "First User",
  "expires_at": "2026-10-28T06:45:07.390237Z"
}
```

#### 6. Join Trip
- You must have *join code* to use in UI
- Request: *POST*  `{API_URL}/v1/trips/join`
- Payload:
```json
{
  "code": "C7FKK7"
}
```
- Response: 
```json
{
  "id": 17,
  "name": "Trip 17",
  "my_role": "member",
  "my_status": "joined",
  "status": "live",
  "ended_reason": null,
  "member_count": 2,
  "started_on": "2026-10-16T06:40:37.547618Z",
  "ended_on": null,
  "planned_s": 3600,
  "actual_s": 235,
  "expires_at": "2026-10-16T07:40:37.547618Z",
  "remaining_s": 3364,
  "join_code": "C7FKK7"
}
```

#### 7. View a trip detail
-  Trips info and its members info. User must be a trip member to view it.
- Request: *GET*  `{API_URL}/v1/trips/<trip_id>`

- Response: 
```json
{
  "trip": {
    "id": 17,
    "name": "Trip 17",
    "my_role": "owner",
    "my_status": "joined",
    "status": "live",
    "ended_reason": null,
    "member_count": 2,
    "started_on": "2026-10-16T06:40:37.547618Z",
    "ended_on": null,
    "planned_s": 3600,
    "actual_s": 129,
    "expires_at": "2026-10-16T07:40:37.547618Z",
    "remaining_s": 3470,
    "join_code": "C7FKK7"
  },
  "members": [
    {
      "user_id": 2,
      "full_name": "First User",
      "role": "owner",
      "lat": -90.0,
      "lon": -180.0,
      "speed": 400.0,
      "fix_age_s": 1163
    },
    {
      "user_id": 4,
      "full_name": "Second User",
      "role": "member",
      "lat": -90.0,
      "lon": -180.0,
      "speed": 400.0,
      "fix_age_s": 1711
    }
  ]
}
```


#### 8. Rename Trip
- As trip owner you can rename trip
- Request: *PATCH*  `{API_URL}/v1/trips/<trip_id>`
- Payload: 
```json
{
  "name":"Renamed Trip"
}
```
- Response: 
```json
{
  "id": 10,
  "name": "Renamed Trip",
  "my_role": "owner",
  "my_status": "joined",
  "status": "live",
  "ended_reason": null,
  "member_count": 1,
  "started_on": "2026-10-15T11:15:19.347201Z",
  "ended_on": null,
  "planned_s": 300,
  "actual_s": 12,
  "expires_at": "2026-10-15T11:20:19.347201Z",
  "remaining_s": 287,
  "join_code": "AMEFGP"
}
```


---

#### 9. Send Live location to server
- Send after creating or joining trip and stop sending if trip is ended or user is out(left or kicked) from trip
- Request: *POST*  `{API_URL}/v1/ingest/live`
- Payload: 
```json
{
  "lat": -90,
  "lon": -180,
  "timestamp": 0,
  "accuracy": 100000,
  "altitude": 0,
  "speed": 400,
  "bearing": 0,
  "batt": 100,
  "charge": true
}
```
- Response: 
```json
{
  "accepted": true,
  "trip_ids": [],
  "fix_age_s": 0
}
```
- If no **trip_ids** then you got that you are no longer on any trips

#### 10. Trip History
-  Get various positions of on-going trip as a trip member.
- Request: *GET*  `{API_URL}/v1/trips/<trip_id>/history`



#### 11. End Trip
- As trip owner you can end trip in middle of trip
- Request: *POST*  `{API_URL}/v1/trips/<trip_id>/end`
- Payload: *No Body*
- Response: 
```json
{
  "id": 16,
  "name": "Trip 16",
  "my_role": "owner",
  "my_status": "joined",
  "status": "ended",
  "ended_reason": "owner",
  "member_count": 1,
  "started_on": "2026-10-16T06:21:44.687341Z",
  "ended_on": "2026-10-16T06:40:24.883825Z",
  "planned_s": 3600,
  "actual_s": 1120,
  "expires_at": "2026-10-16T07:21:44.687341Z",
  "remaining_s": 0,
  "join_code": null
}
```

#### 12. Leave Trip
- Members can also left trip. Once you left you can also rejoin trip.
- Request: *DELETE*  `{API_URL}/v1/trips/<trip_id>/members/me`
- Payload: *No Body*
- Response: *No Response Body - Just 204 status code*

#### 13. Kick Member from Trip
- As trip owner kick annoying members
- Request: *DELETE*  `{API_URL}/v1/trips/<trip_id>/members/<user_id>`
- Payload: *No Body*
- Response: *No Response Body - Just 204 status code*

#### 14. Public View URL of trip
- Anyone with link can view it
- Request: *GET*  `{API_URL}/v1/trips/<trip_id>/view-link`
- Payload: *No Body*
- Response: 
```json
{
  "view_link": "https://tracker.baato.io/trackme/s/2f76e9b37bd4e5d5af4937eca87971110873e92b17ebe76b0f09ab49456237b4"
}
```

#### 15. Rotate trip public link
- If someone unintended accidentally gets link
- Request: *POST*  `{API_URL}/v1/trips/<trip_id>/view-link/rotate`
- Payload: *No Body*
- Response: 
```json
{
  "view_link": "https://tracker.baato.io/trackme/s/2f76e9b37bd4e5d5af4937eca87971110873e92b17ebe76b0f09ab49456237b4"
}
```


#### 16. Delete Trip
- Owner can delete trip. Also it must be ended before deleting.
- Request: *DELETE*  `{API_URL}/v1/trips/<trip_id>`
- Payload: *No Body*
- Response: *No Response Body - Just 204 status code*

---

## Realtime (Socket.IO)

**What the SDK implements, and from where.** Sources are the API spec (`openapi.json`), the TrackMe API usage notes above, and the socket server's event definitions provided by the backend team. Nothing is taken from the public share page's HTML or JavaScript; that page is a separate client of the backend, not a spec. Where the sources don't define something, the table says **"to be documented by backend"**. The SDK then implements the shape stated here, and reports any mismatch through `TrackMe.debugLogger` instead of guessing.

| Item | Value | Source |
|---|---|---|
| Endpoint | `TrackMeConfig.socketUrl` + `/socket.io/` (Engine.IO v4, WebSocket transport). Default `https://tracker.baato.io/trackme` | deployment (configurable) |
| Heartbeat | the server pings; the client answers. Values come from the server's open packet | Engine.IO v4 protocol |
| Rooms | `trip:<id>` for joined members, `share:<hash>` for view-link watchers | socket server |

### Connect and authenticate

- **Members:** Socket.IO `auth: { token: "<access_token>" }`.
- **Invalid or expired token:** the connection is refused (`CONNECT_ERROR`). The SDK asks the token provider once, then reports `AuthenticationFailed`.
- **No token:** anonymous; can only `join_share`.

### Client → server (answers come through the ack)

| Emit | Payload the SDK sends | Ack | Source |
|---|---|---|---|
| `join_trip` | `{ trip_id }` (number) | `{ ok: true, room_id }` or `{ ok: false, error }` | socket server |
| `join_share` | `{ token }`: the **view token** | same | socket server |

Join errors the SDK maps (the server's exact error strings):

| `error` | `TrackMeError` |
|---|---|
| `Not authenticated` | `Unauthorized` |
| `Trip has ended` | `Conflict` |
| `Share not found or expired` | `NotFound` |
| `Invalid trip_id`, `Token required` | `Validation` |
| anything else (e.g. a membership check's HTTP detail) | `Unknown(message)` |

- One trip room per socket; joining another trip switches rooms.
- There is no leave event, so stopping means disconnecting.

### Server → client: the payloads the SDK decodes

The SDK decodes each payload with the fields below; extra fields are ignored. A payload without the required fields, or with other key names, is **dropped and reported** to `TrackMe.debugLogger`.

| Event | Payload (required **bold**) | Source |
|---|---|---|
| `member_changed` | **`trip_id`**, **`user_id`**, `display_name`, **`change`** | socket server |
| `evicted` | `reason`, `trip_id` | socket server |
| `trip_position` | `trip_id`, **`member_user_id`**, `display_name`, **`lat`**, **`lon`**, `speed`, `fix_age_s` | **to be documented by backend.** These are the names the server sends today: decoding `user_id` / `full_name` (TripDetailMember's names) dropped every live message, while these names decode |
| `share_position` | same as `trip_position` | **to be documented by backend** |
| `trip_ended` | `trip_id`, `reason` (both optional) | **to be documented by backend** (the event exists; its payload isn't specified yet) |
| `share_ended` | none needed | socket server |

### Proposed: sharing state (pause shows at once on other phones)

**Status: proposed to the backend; the SDK already implements its side.** Today a paused phone just stops uploading, so others can only notice the silence: "live" turns into "N s ago" after about 45 s. With this change a pause, resume or stop shows on every member's phone at once.

What the backend adds. It's three small things, and all of them are additive, so nothing existing changes:

1. **`POST /api/v1/ingest/state`**: same auth as `/ingest/live`.
   ```json
   { "state": "sharing" }          // "sharing" | "paused" | "stopped"
   ```
   Response `200` (any body; the SDK ignores it). The server stores the value per user, next to their last position. An unknown `state` gets `422`.
2. **Broadcast:** for each live trip the user is a member of, emit the existing **`member_changed`** event to its room with `change` set to the new state:
   ```json
   { "trip_id": 17, "user_id": 9, "display_name": "Sita", "change": "paused" }
   ```
   Only when the stored value changes. `"joined"` (and any other existing values) keep their meaning.
3. **`TripDetailMember.sharing_state`**: `"sharing" | "paused" | "stopped" | null` in `GET /api/v1/trips/{id}`, so an app that opens later, or reconnects, sees it too. Socket events aren't replayed. `null` = never reported.

Optional, in the same spirit: treat a successful `/ingest/live` as `"sharing"` if the stored value was something else, and set `"stopped"` when a user's last live trip ends.

What the SDK does now:

| SDK call | Sends |
|---|---|
| `sharing.start()` | `{"state":"sharing"}` |
| `sharing.pause()` | `{"state":"paused"}` |
| `sharing.resume()` | `{"state":"sharing"}` |
| `sharing.stop()`, permission revoked, location off, background refused | `{"state":"stopped"}` (not after `NoActiveTrips` or `Unauthorized`; `dispose()` sends it best-effort) |

- Only the newest state is sent, in order, so a quick pause → resume can't arrive reversed. It's fire-and-forget: a failure, including today's `404`, changes nothing about sharing.
- Receiving: `member_changed` with `change` = `sharing` / `paused` / `stopped` → `TrackMeEvent.MemberSharingChanged(tripId, userId, displayName, state)`; any other `change` → `MemberChanged` as before. `TripMember.sharingState` reads the new field (null today).
- The samples show **paused** / **not sharing** on the member chip at once, unless a position arrived after that report. Without the backend support they fall back to the 45-second rule.

### Not part of the SDK

- **View tokens:** the API returns the public link as a URL (`view_link`), but `join_share` needs the token. The SDK's `watchShare(viewToken)` takes the token, and an app that only has the link takes it out itself. Proposal for the backend: include `view_token` in the view-link and create-trip responses.
- **The share page's polling endpoint** (`/trackme/s/<token>/position`) isn't in the API spec, so the SDK doesn't use it.
