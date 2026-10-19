# Activity recording

Open **Activity → Record an activity**, choose a sport, and tap **Start**. Frem supports walking, hiking (including trail walks), running, trail running, cycling, mountain biking, gravel cycling, and e-bike rides. Your last sport is remembered.

Allow **precise location while using Frem** when Android asks. Notification access makes recording controls available from the notification shade. GPS starts only after you tap Start; recording does not depend on Health Connect or the daily phone step counter.

## While recording

- The map shows your recorded route, its start, and your last recorded position. Pan to explore, use **Follow last recorded fix** to follow again, or **Fit entire recorded route** to see the whole outing.
- Recordings continue when you lock the phone, switch apps, or leave the recording screen. Return through the ongoing notification, the Activity card, or the return-to-activity button elsewhere in Frem.
- **Pause** stops location collection and the recording timer. **Resume** begins a new segment. Movement during a pause is not added to the route or distance.
- **Finish** saves the outing and opens its details. **Discard activity** asks for confirmation before deleting it.
- Waiting for GPS or a lost signal is shown explicitly. Time can continue without a location fix; missing distance is not invented.

## Metrics

- **Distance** is calculated from accepted GPS points in each continuous recording segment. Stale or implausible fixes and stationary drift are filtered.
- **Recording time** includes time while recording, including stationary stops unless you pause. It excludes manual pauses and recovered interruptions.
- **Elapsed time** is the wall-clock span from Start to Finish, including pauses and interruptions.
- **Average speed/pace** uses recording time. It is not an automatically detected moving-time estimate. Metric and imperial units follow Settings.
- **Estimated ascent** is shown only when sufficiently accurate elevation observations are available. GPS elevation remains an estimate.
- Recorded session distance is separate from all-day phone/Health Connect distance, avoiding adding overlapping measurements together.

## Maps and connectivity

The full map uses OpenStreetMap’s standard raster tiles and displays OpenStreetMap attribution. The map needs a connection for areas not already cached. Frem caches previously viewed tiles, with a 100 MiB tile-cache limit, and respects server expiry and cache validation. It does not download offline map areas or prefetch regions.

GPS recording and saving work without mobile data. If the basemap cannot load, the locally recorded track and recording controls remain available. Map rendering and tile requests are suspended when the map is off-screen. Clear cached tiles under **Settings → Map cache**; this does not delete activity data.

Location requests use high accuracy at one-second intervals for running/cycling, two seconds for walking, and three seconds for hiking. Screen-off delivery can batch fixes for five seconds (six for hiking), retaining their original sample times. Keeping the screen off saves display and rendering power. Actual battery use and GPS accuracy depend on the phone, reception, and surroundings.

## History, editing, and export

The Activity screen combines your recordings with Health Connect sessions and identifies their sources. Filter by sport, open a Frem recording for its map and statistics, or edit its title and sport. Recordings retain the time zone in which they began.

- **Settings → Export CSV** includes completed activity summaries without precise coordinates.
- **Activity details → Export GPX** exports the precise route and point timestamps in GPX 1.1. Pauses and signal gaps remain separate track segments.
- **Delete activity** removes the activity and its route after confirmation.

GPS sessions are stored locally and are not automatically written to Health Connect or uploaded. Loading maps contacts OpenStreetMap and reveals the viewed map areas and connection information to its servers.

## Interrupted recordings

Force-stopping Frem, stopping it through Android’s active-app control, rebooting, or an unexpected process interruption stops capture. On reopening, Frem preserves the last committed points and recording time. Choose **Resume** to start another segment or **Finish** to keep the saved portion. Missing locations and time are not reconstructed.

Android and some manufacturers can restrict long-running apps. If a particular phone repeatedly interrupts recordings, check Frem’s battery and location settings. The app keeps recoverable checkpoints rather than assuming uninterrupted capture.
