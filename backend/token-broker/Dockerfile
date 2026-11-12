FROM node:20-alpine
WORKDIR /srv/token-broker
COPY package.json server.mjs ./
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
