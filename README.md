# bSkyFetcher

**Note: There is minimal error checking in this application.**

## Steps
1. Compile and package the code, or download the executable.
2. Create a directory, `bSkyFetch`, in the same directory as the executable with the following files:
   ```
   bSkyFetch/
   ├── cache.json
   └── fetch.properties
   ```
3. Copy-paste all settings `fetch_sample.properties` to `fetch.properties` and edit the settings
4. Ensure `cache.json` has setting: `{lastTimestamp:""}`
5. Run the executable.