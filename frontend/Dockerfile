FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY next.config.mjs ./
COPY pages ./pages
COPY components ./components
COPY styles ./styles
ARG PORTAL_BACKEND_URL=http://backend:8080
ENV PORTAL_BACKEND_URL=${PORTAL_BACKEND_URL} NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:24-bookworm-slim
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1
COPY --from=build --chown=node:node /app /app
USER node
EXPOSE 3000
CMD ["node", "node_modules/next/dist/bin/next", "start", "--hostname", "0.0.0.0"]
