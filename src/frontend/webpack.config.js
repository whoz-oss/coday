const path = require('path');

module.exports = {
    entry: './src/frontend/app.ts', // Correct entry point
    module: {
        rules: [
            {
                test: /\.tsx?$/, // Handle .ts and .tsx files
                use: 'ts-loader',
                exclude: /node_modules/,
            },
        ],
    },
    resolve: {
        extensions: ['.tsx', '.ts', '.js'],
        alias: {
            shared: path.resolve(__dirname, '../shared'),
        },
    },
    output: {
        filename: 'app.js', // Output bundle file
        path: path.resolve(__dirname, '../../static/dist'), // Output directory
    },
    mode: 'development', // or 'production'
};
