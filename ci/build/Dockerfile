FROM europe-west1-docker.pkg.dev/whoz-196313/docker/coday:master-wrapper

# Set the working directory
WORKDIR /root/app

# Copy the frontend files to the working directory
COPY frontend ./frontend

# Copy the backend files to the working directory
COPY backend ./backend

# Install backend dependencies
RUN cd backend && \
    yarn install

# Start the server
CMD ["node", "backend/server.js"]
