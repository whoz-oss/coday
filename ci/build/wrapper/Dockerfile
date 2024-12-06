FROM ubuntu:24.04

# Avoid prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Set bash as the default shell
SHELL ["/bin/bash", "-c"]

# Install basic dependencies
RUN apt-get update && apt-get install -y \
    apt-transport-https \
    bash \
    ca-certificates \
    curl \
    dpkg \
    git \
    gnupg \
    jq \
    libc6 \
    lsb-release \
    python3 \
    software-properties-common \
    unzip \
    wget \
    zip \
    && rm -rf /var/lib/apt/lists/*

# Force /bin/sh to be bash
RUN rm /bin/sh && ln -s /bin/bash /bin/sh

# Install Docker
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
    | tee /etc/apt/sources.list.d/docker.list > /dev/null \
    && apt-get update \
    && apt-get install -y docker-ce-cli \
    && rm -rf /var/lib/apt/lists/*

## Install Google Cloud SDK using apt
#RUN echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" | tee -a /etc/apt/sources.list.d/google-cloud-sdk.list \
#    && curl https://packages.cloud.google.com/apt/doc/apt-key.gpg | apt-key --keyring /usr/share/keyrings/cloud.google.gpg add - \
#    && apt-get update \
#    && apt-get install -y google-cloud-cli \
#    && rm -rf /var/lib/apt/lists/*

# Install Node.js 23.x
RUN curl -fsSL https://deb.nodesource.com/setup_23.x | bash - \
    && apt-get update \
    && apt-get install -y nodejs \
    && rm -rf /var/lib/apt/lists/*

# Install Yarn
RUN npm install --global yarn
# Add Yarn's global install location to PATH
ENV PATH /root/.npm-global/bin:$PATH

# Install SSH client
RUN apt-get update && apt-get install -y openssh-client \
    && rm -rf /var/lib/apt/lists/*

# Download and install sops
RUN wget https://github.com/mozilla/sops/releases/download/v3.7.3/sops-v3.7.3.linux -O /usr/local/bin/sops \
    && chmod +x /usr/local/bin/sops

ENV HOME_FOLDER /root/app

# Ensure shell scripts use bash
RUN echo '#!/bin/bash' > /etc/profile.d/bash.sh && \
    echo 'export SHELL=/bin/bash' >> /etc/profile.d/bash.sh && \
    echo 'export BASH=/bin/bash' >> /etc/profile.d/bash.sh

# Expose the port on which the server will run (adjust if necessary)
EXPOSE 3000
