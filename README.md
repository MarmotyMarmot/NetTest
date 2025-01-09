# NetTest

## Overview
NetTest is a mobile application designed to test network communication performance. The application allows users to send and receive data between devices, enabling them to measure transmission efficiency and save results for further analysis. Key features include video frame extraction, CSV export, and media selection for testing.

### App UI


## Features
- Send images extracted from user-selected videos.
- Receive and process text responses.
- Measure transmission performance end-to-end.
- Export results to CSV for detailed analysis.
- Select and process media files.

## Manual
### Running the Application
1. Clone the repository to your local machine.
2. Ensure all dependencies are installed as specified in the requirements.
3. Launch the application on a compatible mobile device.
4. Follow the on-screen instructions to select a video and initiate the testing process.

### Using the Multi-Threaded Backend
1. Switch to the multi-threaded backend for improved performance under high load conditions.
2. The multi-threaded backend code is located in the `master` branch of this repository.
3. Follow the "Running the Application" steps above to set up and deploy.

### Using the Single-Threaded Backend
1. Use the single-threaded backend for simplicity and lower resource usage.
2. The single-threaded backend code is located in the `single-thread` branch of this repository.
3. Follow the "Running the Application" steps above to set up and deploy.

## Disclaimer
1. The app supports testing on select devices and may require specific configurations.
2. Processing includes transmission, server-side computation, and result retrieval.
3. The application is under active development, and features may change over time.

## Contributing
We welcome all contributions! If you'd like to contribute to this project:
- Fork the repository.
- Implement your changes.
- Submit a pull request for review.

## License
This project is licensed under the MIT License. See the LICENSE file for details.
