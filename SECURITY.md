# Security

## Reporting a problem
Found a bug that could expose someone's data or keys? Do not post it in a
public issue. Contact the owner privately through the GitHub profile
(https://github.com/philfesters) and give steps to reproduce.

## Keys and secrets
- Never commit API keys, tokens or vault exports. `.gitignore` blocks the
  usual file names, but it is not a safety net for a key pasted into code.
- If a key was ever pushed, treat it as leaked: revoke it, create a new one,
  then clean the history.
- API keys in the app are stored in the encrypted vault on the device only.

## Supported version
Only the latest release gets fixes.
