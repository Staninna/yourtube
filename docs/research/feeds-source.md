# Feeds source: RSS, the videos page, or both

Measured 8 November 2026 from this machine (Dutch network, curl 8, IPv4 and IPv6). Question: where
does Feeds read a channel's newest uploads without a login or an API key, now that
`https://www.youtube.com/feeds/videos.xml?channel_id=UC...` keeps answering 404?

## What the feed endpoint does

- **Request shape makes no difference.** At 01:31 UTC every request got HTTP 404 with
  `server: YouTube RSS Feeds server` and Google's "Error 404 (Not Found)!!1" page. That included
  Rick Astley, Veritasium, MKBHD, Fireship and LTT, five User-Agents (Chrome mobile, Chrome
  desktop, Firefox, the Android WebView, the app's old `Mozilla/5.0 (Android) Tube`), with and
  without `SOCS` (both the reject-all value and `CAI`), `Accept-Language`, HTTP/1.1 vs HTTP/2, and
  `app=desktop`. `user=` and `playlist_id=UU...` feeds 404 the same way, and so does a made-up
  channel id, so the 404 says nothing about the channel.
- **The host doesn't matter.** `m.youtube.com/feeds/videos.xml` answers 302 to the `www` URL with
  `app=desktop`, which then 404s.
- **Retries and rate:** 12 back-to-back requests and 6 spaced 5 s apart all got 404. IPv6 got a 500.
- **It flaps.** Six minutes later the same endpoint answered a mix: 20 channels fetched 6 at a time
  gave 2 × 200, 16 × 404 and 2 × 500. Then one channel polled once a second gave
  `404 500 200 200 404 200`. During a flap a second try often lands; during an outage nothing does.

## What the channel's videos page does

`https://m.youtube.com/channel/UC.../videos` with a Chrome-on-Android UA, `SOCS` (reject all) and
`Accept-Language: en` answered **200 for 20 of 20 channels** at concurrency 6, in 3.7 s total for
both sources. Without `SOCS` it answers 302 to `consent.youtube.com`, which is why `ChannelResolver`
already sends it. The page (about 550 KB) embeds `ytInitialData` as a single-quoted JS string with
`\xHH` escapes. Its grid holds the newest 30 uploads as `compactVideoRenderer` with `videoId`,
`title`, and `publishedTimeText` ("3 days ago"). `metadata.channelMetadataRenderer` has the name and
`externalId`. There are no exact dates, no Shorts (they live on `/shorts`), and no live streams
(`/streams`).

## Reports elsewhere

- Google AI developer forum thread, Dec 2025 to May 2026: intermittent 404s on `feeds/videos.xml`
  for public channels, often clustered in a time window, sometimes with 500s. Changing the User-Agent
  did not help. No answer from Google.
  <https://discuss.ai.google.dev/t/youtube-rss-feed-endpoint-returns-404-errors/113379>
- Feeder's help page (updated 7 Nov 2026): YouTube's feed server "sometimes" answers 404 for every
  feed "for hours at a time" and recovers on its own.
  <https://feeder.co/help/rss/youtube-feeds/>
- FreeTube builds subscriptions from each channel's page by default, offers RSS as a faster
  option with less data, and falls back to the other method when one fails.
  <https://docs.freetubeapp.io/usage/subscriptions/>
- NewPipe's "fast mode" uses the dedicated feed (RSS) and otherwise extracts the channel.
  <https://newpipe.net/blog/release/pinned/newpipe-0.19.0-released/>
- I found no NewPipe, Invidious or yt-dlp issue specific to the late-2026 404s.

## Decision

Both, per channel, in `YouTubeFeedFetcher`:

1. **RSS first**, two tries 400 ms apart, retrying on 404 and 5xx (it flaps). RSS gives exact
   publish times in about 40 KB.
2. **Then the videos page**, up to three tries with 1 s and 2 s backoff, on 5xx only. A 429 asks
   for less traffic, so neither source retries it.
   Times come from "N units ago" and are rounded. Feeds writes every age in YouTube's style, so a
   rounded age reads as YouTube showed it. Videos of the same age keep the page's order.
3. **No connection** (no network, or the host does not resolve) stops at the first try and skips
   the page. A refused connection is YouTube's side, so the page is still tried.

Both requests go through `YouTubeHttp`, the same anonymous request shape `ChannelResolver` uses:
mobile Chrome UA, `SOCS` reject-all, no other cookie. No Invidious or Piped instance: the page
works directly, and a third-party instance would see every subscription.

Known gaps: the page path drops Shorts and live streams, while RSS includes them, so a channel's
list can change shape between refreshes (stage 6 filters Shorts). Ages from the page are coarse
("1 month ago"), so videos from different channels can interleave out of true order. Diagnostics
count `feed.rss.fallback` and `feed.unreadable` (markup drift).
