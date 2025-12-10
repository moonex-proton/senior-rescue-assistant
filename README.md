# Senior Rescue Assistant

Senior Rescue Assistant is a frontend for a voice-first “rescue service” that helps older adults safely use their smartphones without learning tech jargon, menus, or complex gestures. Instead of teaching them how phones work, the assistant behaves like a calm human helper: it listens to a simple request (“I want to call my daughter”, “Why is there no internet?”) and then leads the user through the task step-by-step with short voice prompts and clear visual highlights.

## Rescue Service Concept

The underlying concept is built for people on the far side of the digital divide: outdated devices, no updates, and almost no understanding of apps, accounts, or system status. The assistant works as an always-available “red button” on the home screen: when pressed, it uses an LLM together with Android Accessibility (screen tree inspection and overlays) to understand what is currently on screen and guide the user with one tiny, concrete instruction at a time (e.g. “Now press the green button with the phone symbol”), waiting for the action and checking the result before moving on.

## Key Ideas Behind the Project

- **Designed for seniors, not power users** – no tech terms, no complex UI, one visible entry point (the “red button”).
- **Step-by-step rescue flows** – short instructions + visual highlighting via Accessibility overlays + result checks before the next step.
- **Proactive protection** – reacts to risky situations like silent incoming calls, low battery, airplane mode, ANR dialogs, or muted microphone.
- **Voice-only “settings”** – name, language, and speech speed are adjusted with natural phrases like “call me Anna” or “talk slower”, no menus required.

The long-term goal is to make modern smartphones survivable for very old users: the assistant acts as a psychological “rescuer”, calming the user, clarifying their real goal (“see my granddaughter’s photo”, not “open Gallery”), and then safely navigating them through the necessary screens.
