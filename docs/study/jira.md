2. Sprint and Agile Board Management
   Retrieve Active Sprints
   Endpoint:

GET /rest/agile/1.0/board/{boardId}/sprint?state=active
This will list all active sprints for a particular board.

JQL Expression: Not applicable directly since the endpoint is for retrieving active sprints directly by board ID.

Retrieve Sprint Issues
Endpoint:

GET /rest/agile/1.0/sprint/{sprintId}/issue
This will retrieve all issues associated with a specific sprint.

JQL Expression:

sprint = {sprintId}
Retrieve Board Configuration
Endpoint:

GET /rest/agile/1.0/board/{boardId}/configuration
This returns the configurations for a specified board.

3. Backlog and Roadmap Management
   Retrieve Backlog Issues
   Endpoint:

GET /rest/agile/1.0/board/{boardId}/backlog
This will list all issues in the backlog for a specific board.

JQL Expression:

status = "Backlog"
Retrieve Epic Details
Endpoint:

GET /rest/agile/1.0/epic/{epicId}
This provides detailed information about a specific epic.

JQL Expression:

"Epic Link" = {epicId}

7. User and Team Management
   Retrieve Users in a Group
   Endpoint:

GET /rest/api/3/group?groupname={groupname}
This will list all users within a specified group.

JQL Expression: Not directly applicable as this is a user management endpoint.

Retrieve Assignee Issues
Endpoint:

GET /rest/api/3/search?jql=assignee={username}
This will list all issues assigned to a specific user.

JQL Expression:

assignee = {username}
These endpoints and JQL expressions will help product owners and scrum masters retrieve necessary data to manage their
projects effectively.