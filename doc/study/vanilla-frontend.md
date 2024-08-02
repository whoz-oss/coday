# Building a Chat Application with Vanilla JavaScript

## Overview

This document outlines the core components and considerations for building a chat application using plain JavaScript,
HTML, and CSS. While it's possible to build complex features, this approach focuses on the fundamentals of creating a
basic chat interface.

## Core Components

- HTML Structure:

    - A container for the message list.
    - A text input or textarea for user input.
    - A button or keyboard shortcut to send messages.

- CSS Styling:

    - Define the appearance of messages, input fields, and overall layout.
    - Consider using flexbox or grid for responsive design.

- JavaScript Logic:

    - Handle user input and message submission.
    - Append new messages to the message list.
    - Implement scrolling behavior.
    - Optionally, handle markdown rendering.

Code Examples

```HTML
<!DOCTYPE html>
<html>
<head>
    <title>Chat App</title>
    <link rel="stylesheet" href="styles.css">
</head>
<body>
<div id="message-list"></div>
<textarea id="message-input"></textarea>
<button id="send-button">Send</button>
<script src="script.js"></script>
</body>
</html>
```

```JavaScript
const messageList = document.getElementById('message-list');
const messageInput = document.getElementById('message-input');
const sendButton = document.getElementById('send-button');

function sendMessage() {
    const message = messageInput.value.trim();

    if (message) {
// Append message to the message list
        const messageElement = document.createElement('div');
        messageElement.textContent = message;
        messageList.appendChild(messageElement);

        // Clear input field
        messageInput.value = '';

        // Scroll to bottom
        messageList.scrollTop = messageList.scrollHeight;

    }
}

sendButton.addEventListener('click', sendMessage);
messageInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
        sendMessage();

    }
});
```

Use code with caution.

## Additional Considerations

- Markdown Rendering:

    - Use a library like marked to convert Markdown to HTML.
    - Sanitize the output to prevent XSS attacks.
    - Create a function to handle Markdown messages:

```JavaScript
function renderMarkdownMessage(markdownText) {
    const html = marked(markdownText);
    const messageElement = document.createElement('div');
    messageElement.innerHTML = html;
    messageList.appendChild(messageElement);
}
```

- Message Structure:
    - Consider using a more structured approach for messages, such as creating message objects with properties for text,
      timestamp, author, etc.
    - Use templates or create custom elements for complex message layouts.

- Error Handling:
    - Implement error handling for network requests and other potential issues.
    - Display informative error messages to the user.
- User Experience:
    - Improve the user experience with features like message timestamps, message editing, and deletion.
    - Consider using CSS animations for smoother interactions.
- Limitations
    - Basic functionality: This implementation covers the core features of a chat application but lacks advanced
      features like real-time updates, user authentication, and message history.
    - Performance: For large message lists, performance might degrade. Consider optimizing DOM updates or exploring
      virtual scrolling techniques in the future.
    - User interface: The provided code focuses on the core logic and doesn't include detailed CSS styling for a
      polished user interface.

By building upon this foundation and addressing the mentioned considerations, you can create a more robust and
user-friendly chat application.