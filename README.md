# mule-railway

Mule Runtime Community Edition 4.12.0 packaged for Railway.

Community Edition has no deployment API, so this image adds a small admin front (`admin/Admin.java`, JDK only) next to the runtime:

- `GET /health` (open): `{"status":"UP","runtime":"4.12.0"}`
- `GET /admin/apps` (Bearer `MULE_ADMIN_TOKEN`): deployed application names
- `PUT /admin/apps/{name}` (Bearer): body is a Mule application archive built by `mule-maven-plugin` (`-mule-application.jar`); the runtime hot-deploys it
- `DELETE /admin/apps/{name}` (Bearer): undeploys it
- `/gateway/*`: forwarded to `127.0.0.1:8081`, where applications declare their HTTP listener

Applications live on the volume mounted at `/data` (`/data/apps`) and survive redeploys. `MULE_APP_URLS` (comma separated) fetches archives on boot.

The image is built and pushed to `ghcr.io/bon5co/mule-railway` by GitHub Actions on every push to `main`.
