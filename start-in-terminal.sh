#!/bin/bash

./update.sh
yarn install --frozen-lockfile
yarn run start
