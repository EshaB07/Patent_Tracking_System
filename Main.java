import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

public class Main {
    private static final Gson GSON = new Gson();
    private static final int PORT = Integer.parseInt(
            System.getenv().getOrDefault("PORT", "8080"));

    private static Connection getConnection() throws SQLException {
        String url = System.getenv().getOrDefault(
                "DB_URL", "jdbc:mysql://localhost:3306/miniproject?serverTimezone=Asia/Kolkata");
        String user = System.getenv().getOrDefault("DB_USER", "root");
String password = System.getenv().get("DB_PASSWORD");

if (password == null || password.isBlank()) {
    throw new IllegalStateException(
        "DB_PASSWORD environment variable is not configured."
    );
}          
        return DriverManager.getConnection(url, user, password);
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT),
                0);
        server.createContext("/api/dashboard", Main::dashboard);
        server.createContext("/api/users", Main::getUsers);
        server.createContext("/api/contracts", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                createOwnershipContract(exchange);
            } else {
                getContracts(exchange);
            }
        });

        server.createContext("/api/owners", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                registerOwner(exchange);
            } else {
                getOwners(exchange);
            }
        });
        server.createContext("/api/reports/status",
                Main::getPatentStatusReport);
        server.createContext("/api/reports/contract-amounts",
                Main::getContractAmountReport);
        server.createContext("/api/reports/active-contracts",
                Main::getActiveContractsReport);
        server.createContext("/api/reports/multiple-contracts",
                Main::getMultipleContractsReport);
        server.createContext("/api/reports/total-per-patent",
                Main::getTotalPerPatentReport);
        server.createContext("/api/reports/payment-types",
                Main::getPaymentTypeReport);
        server.createContext("/api/contract-duration",
                Main::getContractDuration);
        server.createContext("/api/collaborators",
                Main::getPatentCollaborators);
        server.createContext("/api/collaborators/assign",
                Main::assignCollaborator);
        server.createContext("/api/patents", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                createPatent(exchange);
            } else {
                patents(exchange);
            }
        });
        server.createContext("/api/patents/status", Main::updatePatentStatus);
        server.createContext("/api/patents/create", Main::createPatent);
        server.createContext("/", Main::serveFrontend);
        server.setExecutor(null);
        server.start();
        System.out.println("PatentVault is running at http://localhost:" +
                PORT);
        System.out.println("API test: http://localhost:" + PORT +
                "/api/patents");
    }

    private static void dashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use GET for this endpoint."));
            return;
        }

        String sql = """
                            SELECT
                              (SELECT COUNT(*) FROM PATENT) AS totalPatents,
                              (SELECT COUNT(*) FROM PATENT WHERE Status = 'Granted') AS
                grantedPatents,
                              (SELECT COUNT(*) FROM USERS) AS registeredUsers,
                              (SELECT COUNT(*) FROM OWNS) AS ownershipContracts
                            """;

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (rs.next()) {
                result.put("totalPatents", rs.getInt("totalPatents"));
                result.put("grantedPatents", rs.getInt("grantedPatents"));
                result.put("registeredUsers",
                        rs.getInt("registeredUsers"));
                result.put("ownershipContracts",
                        rs.getInt("ownershipContracts"));
            }
            sendJson(exchange, 200, result);
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of(
                    "error", "Could not load dashboard data. Check database credentials and schema.",
                    "details", safeSqlMessage(e)));
        }
    }

    private static void patents(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use GET for this endpoint."));
            return;
        }

        String sql = """
                SELECT PatentID, Title, Description, Status, CreatedAt
                FROM PATENT
                ORDER BY PatentID
                """;

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patentId", rs.getInt("PatentID"));
                row.put("title", rs.getString("Title"));
                row.put("description", rs.getString("Description"));
                row.put("status", rs.getString("Status"));
                Timestamp created = rs.getTimestamp("CreatedAt");
                row.put("createdAt", created == null ? null : created.toString());
                rows.add(row);
            }
            sendJson(exchange, 200, rows);
        } catch (SQLException e) {
            sendJson(exchange, 500, Map.of(
                    "error", "Could not load patents. Check database credentials and table names.",
                    "details", safeSqlMessage(e)));
        }
    }

    private static void createPatent(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "Use POST for this endpoint."));
            return;
        }

        try {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            Map<String, Object> data = GSON.fromJson(
                    body,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                    }.getType());

            int patentId = ((Number) data.get("patentId")).intValue();
            String title = String.valueOf(data.get("title")).trim();
            String description = data.get("description") == null
                    ? ""
                    : String.valueOf(data.get("description")).trim();
            String status = String.valueOf(data.get("status"));

            if (title.isBlank()
                    || !Set.of("Pending", "Granted",
                            "Terminated").contains(status)) {
                sendJson(exchange, 400, Map.of("error", "Invalid title or status."));
                return;
            }

            String sql = "INSERT INTO PATENT (PatentID, Title, Description, Status) "
                    + "VALUES (?, ?, ?, ?)";

            try (Connection con = getConnection();
                    PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, patentId);
                ps.setString(2, title);
                ps.setString(3, description);
                ps.setString(4, status);
                ps.executeUpdate();
            }

            sendJson(exchange, 201, Map.of("message", "Patent registered successfully."));

        } catch (SQLException | RuntimeException e) {
            sendJson(exchange, 400, Map.of("error", "Could not register patent. Check the ID and input."));
        }
    }

    private static void getUsers(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        if ("POST".equalsIgnoreCase(method)) {
            try {
                String body = new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);

                Map<String, Object> data = GSON.fromJson(
                        body,
                        new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                        }.getType());

                if (data == null || data.get("id") == null
                        || data.get("name") == null
                        || String.valueOf(data.get("name")).trim().isEmpty()) {
                    sendJson(exchange, 400,
                            Map.of("error", "User ID and name are required."));
                    return;
                }

                int userId = ((Number) data.get("id")).intValue();
                String name = String.valueOf(data.get("name")).trim();
                String email = data.get("email") == null
                        ? null
                        : String.valueOf(data.get("email")).trim();
                String phone = data.get("phone") == null
                        || String.valueOf(data.get("phone")).isBlank()
                                ? null
                                : String.valueOf(data.get("phone")).trim();
                String address = data.get("address") == null
                        ? null
                        : String.valueOf(data.get("address")).trim();

                if (userId <= 0 || name.length() > 50
                        || (email != null && email.length() > 50)
                        || (phone != null && !phone.matches("[0-9]{10}"))
                        || (address != null && address.length() > 150)) {
                    sendJson(exchange, 400,
                            Map.of("error", "Please check the user details."));
                    return;
                }

                String sql = "INSERT INTO USERS "
                        + "(UserID, Name, Email, Phone, Address, RegisteredAt) "
                        + "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";

                try (Connection con = getConnection();
                        PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, userId);
                    ps.setString(2, name);
                    ps.setString(3, email);

                    if (phone == null) {
                        ps.setNull(4, java.sql.Types.DECIMAL);
                    } else {
                        ps.setBigDecimal(4, new java.math.BigDecimal(phone));
                    }

                    ps.setString(5, address);
                    ps.executeUpdate();
                }

                sendJson(exchange, 201,
                        Map.of("message", "User registered permanently in MySQL."));

            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(exchange, 400,
                        Map.of("error",
                                "Could not register user. The User ID may already exist, or the database rejected the details."));
            } catch (RuntimeException e) {
                sendJson(exchange, 400,
                        Map.of("error", "Invalid user details."));
            }
            return;
        }

        if (!"GET".equalsIgnoreCase(method)) {
            sendJson(exchange, 405,
                    Map.of("error", "Use GET or POST for this endpoint."));
            return;
        }

        String sql = "SELECT UserID, Name, Email, Phone, Address, RegisteredAt "
                + "FROM USERS ORDER BY UserID";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("UserID"));
                row.put("userId", rs.getInt("UserID"));
                row.put("name", rs.getString("Name"));
                row.put("email", rs.getString("Email"));
                row.put("phone", rs.getString("Phone"));
                row.put("address", rs.getString("Address"));

                Timestamp registered = rs.getTimestamp("RegisteredAt");
                row.put("registeredAt",
                        registered == null ? null : registered.toString());

                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500,
                    Map.of("error", "Could not load users from MySQL."));
        }
    }

    private static void getContracts(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("error", "Use GET for this endpoint."));
            return;
        }

        String sql = "SELECT o.PatentID, p.Title, "
                + "o.OwnerID, ol.OwnerName, o.StartDt, "
                + "o.EndDt, o.Amount, o.PayType, o.PaidAt "
                + "FROM OWNS o "
                + "JOIN PATENT p ON o.PatentID = p.PatentID "
                + "JOIN OWNERSHIP_LOG ol ON o.OwnerID = ol.OwnerID "
                + "ORDER BY o.PatentID";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patentId", rs.getInt("PatentID"));
                row.put("patentTitle", rs.getString("Title"));
                row.put("ownerId", rs.getInt("OwnerID"));
                row.put("ownerName", rs.getString("OwnerName"));
                row.put("startDate", rs.getString("StartDt"));
                row.put("endDate", rs.getString("EndDt"));
                row.put("amount", rs.getInt("Amount"));
                row.put("payType", rs.getString("PayType"));
                row.put("paidAt", rs.getString("PaidAt"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            sendJson(exchange, 500,
                    Map.of("error", "Could not load ownership contracts."));
        }
    }

    private static void createOwnershipContract(HttpExchange exchange)
            throws IOException {

        try {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            Map<String, Object> data = GSON.fromJson(
                    body,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                    }.getType());

            int patentId = ((Number) data.get("patentId")).intValue();
            int ownerId = ((Number) data.get("ownerId")).intValue();
            String startDate = String.valueOf(data.get("startDate"));
            String endDate = data.get("endDate") == null
                    || String.valueOf(data.get("endDate")).isBlank()
                            ? null
                            : String.valueOf(data.get("endDate"));
            int amount = ((Number) data.get("amount")).intValue();
            String payType = String.valueOf(data.get("payType"));

            if (amount <= 0 || startDate.isBlank() || payType.isBlank()) {
                sendJson(exchange, 400,
                        Map.of("error", "Invalid contract details."));
                return;
            }

            String sql = """
                    INSERT INTO OWNS
                    (PatentID, OwnerID, StartDt, EndDt, Amount,
                     PayType, PaidAt, StatusOfPatent)
                    SELECT PatentID, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, Status
                    FROM PATENT
                    WHERE PatentID = ?
                    AND Status <> 'Terminated'
                    """;

            try (Connection con = getConnection();
                    PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, ownerId);
                ps.setDate(2, java.sql.Date.valueOf(startDate));

                if (endDate == null) {
                    ps.setNull(3, java.sql.Types.DATE);
                } else {
                    ps.setDate(3, java.sql.Date.valueOf(endDate));
                }

                ps.setInt(4, amount);
                ps.setString(5, payType);
                ps.setInt(6, patentId);

                int inserted = ps.executeUpdate();

                if (inserted == 0) {
                    sendJson(exchange, 400,
                            Map.of("error",
                                    "Cannot create an ownership contract. The patent either does not exist or has been terminated."));
                    return;
                }

                sendJson(exchange, 201,
                        Map.of("message", "Ownership contract created successfully."));

            }

        } catch (SQLException | RuntimeException e) {
            e.printStackTrace();
            sendJson(exchange, 400,
                    Map.of("error", "Could not create ownership contract."));
        }
    }

    private static void getOwners(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("error", "Use GET for this endpoint."));
            return;
        }

        String query = exchange.getRequestURI().getQuery();
        String patentId = null;

        if (query != null) {
            for (String param : query.split("&")) {
                String[] pair = param.split("=", 2);

                if (pair.length == 2 && pair[0].equals("patentId")) {
                    patentId = pair[1];
                    break;
                }
            }
        }

        String sql;

        if (patentId != null && !patentId.isBlank()) {
            sql = """
                    SELECT DISTINCT ol.OwnerID, ol.OwnerName
                    FROM OWNS o
                    JOIN OWNERSHIP_LOG ol ON ol.OwnerID = o.OwnerID
                    WHERE o.PatentID = ?
                    ORDER BY ol.OwnerName
                    """;
        } else {
            sql = """
                    SELECT DISTINCT OwnerID, OwnerName
                    FROM OWNERSHIP_LOG
                    ORDER BY OwnerName
                    """;
        }

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql)) {

            if (patentId != null && !patentId.isBlank()) {
                ps.setInt(1, Integer.parseInt(patentId));
            }

            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ownerId", rs.getInt("OwnerID"));
                    row.put("ownerName", rs.getString("OwnerName"));
                    rows.add(row);
                }

                sendJson(exchange, 200, rows);
            }

        } catch (NumberFormatException e) {
            sendJson(exchange, 400, Map.of("error", "Invalid patent ID."));
        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500,
                    Map.of("error", "Could not load owners from the database."));
        }
    }

    private static void registerOwner(HttpExchange exchange)
            throws IOException {

        try {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            Map<String, Object> data = GSON.fromJson(
                    body,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                    }.getType());

            int ownerId = ((Number) data.get("ownerId")).intValue();
            String ownerName = String.valueOf(data.get("ownerName")).trim();

            if (ownerName.isBlank()) {
                sendJson(exchange, 400, Map.of("error", "Owner name is required."));
                return;
            }

            String sql = "INSERT INTO OWNERSHIP_LOG (OwnerID, OwnerName) VALUES (?, ?)";

            try (Connection con = getConnection();
                    PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, ownerId);
                ps.setString(2, ownerName);
                ps.executeUpdate();
            }

            sendJson(exchange, 201,
                    Map.of("message", "Owner registered successfully."));

        } catch (SQLException | RuntimeException e) {
            e.printStackTrace();

            sendJson(exchange, 400, Map.of(
                    "error", "Owner registration failed.",
                    "details", e.getClass().getSimpleName()
                            + ": " + String.valueOf(e.getMessage())));
        }
    }

    private static void getPatentStatusReport(HttpExchange exchange)
            throws IOException {

        String sql = "SELECT Status, COUNT(*) AS Total "
                + "FROM PATENT GROUP BY Status";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("status", rs.getString("Status"));
                row.put("total", rs.getInt("Total"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            sendJson(exchange, 500,
                    Map.of("error", "Could not generate patent status report."));
        }
    }

    private static void getContractAmountReport(HttpExchange exchange)
            throws IOException {

        String sql = "SELECT OwnerName, SUM(Amount) AS TotalAmount "
                + "FROM OWNS "
                + "JOIN OWNERSHIP_LOG USING (OwnerID) "
                + "GROUP BY OwnerID, OwnerName "
                + "ORDER BY TotalAmount DESC";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("company", rs.getString("OwnerName"));
                row.put("totalAmount", rs.getBigDecimal("TotalAmount"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            sendJson(exchange, 500,
                    Map.of("error", "Could not load contract amounts."));
        }
    }

    private static void getActiveContractsReport(HttpExchange exchange)
            throws IOException {

        String sql = "SELECT o.PatentID, p.Title, "
                + "ol.OwnerName, o.StartDt, o.EndDt, o.Amount "
                + "FROM OWNS o "
                + "JOIN PATENT p ON o.PatentID = p.PatentID "
                + "JOIN OWNERSHIP_LOG ol ON o.OwnerID = ol.OwnerID "
                + "WHERE CURDATE() BETWEEN o.StartDt AND o.EndDt "
                + "AND p.Status <> 'Terminated' "
                + "ORDER BY o.EndDt";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patentId", rs.getInt("PatentID"));
                row.put("patentTitle", rs.getString("Title"));
                row.put("ownerName", rs.getString("OwnerName"));
                row.put("startDate", rs.getString("StartDt"));
                row.put("endDate", rs.getString("EndDt"));
                row.put("amount", rs.getBigDecimal("Amount"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            sendJson(exchange, 500,
                    Map.of("error", "Could not load active contracts."));
        }
    }

    private static void getMultipleContractsReport(HttpExchange exchange)
            throws IOException {

        String sql = "SELECT p.PatentID, p.Title, "
                + "COUNT(*) AS ContractCount "
                + "FROM OWNS o "
                + "JOIN PATENT p ON o.PatentID = p.PatentID "
                + "GROUP BY p.PatentID, p.Title "
                + "HAVING COUNT(*) > 1 "
                + "ORDER BY ContractCount DESC";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patentId", rs.getInt("PatentID"));
                row.put("patentTitle", rs.getString("Title"));
                row.put("contractCount", rs.getInt("ContractCount"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            sendJson(exchange, 500,
                    Map.of("error", "Could not load multiple-contract patents."));
        }
    }

    private static void getTotalPerPatentReport(HttpExchange exchange)
            throws IOException {

        String sql = "SELECT p.PatentID, p.Title, "
                + "COALESCE(SUM(o.Amount), 0) AS TotalAmount "
                + "FROM PATENT p "
                + "LEFT JOIN OWNS o ON p.PatentID = o.PatentID "
                + "GROUP BY p.PatentID, p.Title "
                + "ORDER BY p.PatentID";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patentId", rs.getInt("PatentID"));
                row.put("patentTitle", rs.getString("Title"));
                row.put("totalAmount", rs.getBigDecimal("TotalAmount"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            sendJson(exchange, 500,
                    Map.of("error", "Could not load total amounts per patent."));
        }
    }

    private static void getPaymentTypeReport(HttpExchange exchange)
            throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("error", "Use GET for this endpoint."));
            return;
        }

        String sql = """
                SELECT PayType, COUNT(*) AS ContractCount
                FROM OWNS
                GROUP BY PayType
                ORDER BY PayType
                """;

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("payType", rs.getString("PayType"));
                row.put("contractCount", rs.getInt("ContractCount"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500,
                    Map.of("error", "Could not load payment mode summary."));
        }
    }

    private static void serveFrontend(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed", "text/plain; charset=UTF-8");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendText(exchange, 404, "Not found", "text/plain; charset=UTF-8");
            return;
        }

        try (InputStream in = Main.class.getResourceAsStream("/webapp/index.html")) {
            if (in == null) {
                sendText(exchange, 500,
                        "Missing src/main/resources/webapp/index.html. Copy your final PatentVault HTML there.",
                        "text/plain; charset=UTF-8");
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control",
                    "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type",
                "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin",
                "http://localhost:" + PORT);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String safeSqlMessage(SQLException e) {
        // Helpful for local debugging; do not expose database errors publicly in a
        // deployed app.
        return e.getMessage() == null ? "Unknown SQL error" : e.getMessage();
    }

    private static void updatePatentStatus(HttpExchange exchange)
            throws IOException {

        if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("error", "Use PUT for this endpoint."));
            return;
        }

        try {
            String body = new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);

            Map<String, Object> data = GSON.fromJson(
                    body,
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                    }.getType());

            int patentId = ((Number) data.get("patentId")).intValue();
            String status = (String) data.get("status");

            if (!Set.of("Pending", "Granted", "Terminated").contains(status)) {
                sendJson(exchange, 400,
                        Map.of("error", "Invalid patent status."));
                return;
            }

            String sql = "UPDATE PATENT SET Status = ? WHERE PatentID = ?";

            try (Connection con = getConnection();
                    PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setString(1, status);
                ps.setInt(2, patentId);

                int updated = ps.executeUpdate();

                if (updated == 0) {
                    sendJson(exchange, 404,
                            Map.of("error", "Patent not found."));
                    return;
                }
            }

            sendJson(exchange, 200,
                    Map.of("message", "Patent status updated successfully."));

        } catch (SQLException | RuntimeException e) {
            sendJson(exchange, 400,
                    Map.of("error",
                            "Could not update status. Check the database rules."));
        }
    }

    private static void getPatentCollaborators(HttpExchange exchange)
            throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("error", "Use GET for this endpoint."));
            return;
        }

        String sql = "SELECT p.PatentID, p.Title, "
                + "COALESCE(GROUP_CONCAT(u.Name ORDER BY u.UserID SEPARATOR ', '), "
                + "'No collaborators listed') AS CollaboratorNames "
                + "FROM PATENT p "
                + "LEFT JOIN CREATES c ON p.PatentID = c.PatentID "
                + "LEFT JOIN USERS u ON c.UserID = u.UserID "
                + "GROUP BY p.PatentID, p.Title "
                + "ORDER BY p.PatentID";

        try (Connection con = getConnection();
                PreparedStatement ps = con.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            List<Map<String, Object>> rows = new ArrayList<>();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("patentId", rs.getInt("PatentID"));
                row.put("patentName", rs.getString("Title"));
                row.put("collaborators", rs.getString("CollaboratorNames"));
                rows.add(row);
            }

            sendJson(exchange, 200, rows);

        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500,
                    Map.of("error", "Could not load patent collaborators."));
        }
    }

    private static void assignCollaborator(HttpExchange exchange)
            throws IOException {

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405,
                    Map.of("error", "Use POST for this endpoint."));
            return;
        }

        try {
            Map<String, Object> input = GSON.fromJson(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>() {
                    }.getType());

            if (input == null
                    || input.get("patentId") == null
                    || input.get("userId") == null) {
                sendJson(exchange, 400,
                        Map.of("error", "Patent ID and User ID are required."));
                return;
            }

            int patentId = ((Number) input.get("patentId")).intValue();
            int userId = ((Number) input.get("userId")).intValue();

            if (patentId <= 0 || userId <= 0) {
                sendJson(exchange, 400,
                        Map.of("error", "IDs must be positive numbers."));
                return;
            }

            String sql = """
                    INSERT INTO CREATES (PatentID, UserID, StatusOfPatent)
                    SELECT PatentID, ?, Status
                    FROM PATENT
                    WHERE PatentID = ?
                    AND Status <> 'Terminated'
                    """;
            try (Connection con = getConnection();
                    PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, userId);
                ps.setInt(2, patentId);

                int inserted = ps.executeUpdate();

                if (inserted == 1) {
                    sendJson(exchange, 201,
                            Map.of("message",
                                    "Collaborator assigned successfully."));
                } else {
                    sendJson(exchange, 400,
                            Map.of("error",
                                    "Cannot assign a collaborator. The patent either does not exist or has been terminated."));
                    return;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, 500,
                    Map.of("error",
                            "Database insertion failed. Check that the Patent ID and User ID exist and that the association is valid."));
        } catch (Exception e) {
            sendJson(exchange, 400,
                    Map.of("error", "Invalid request data."));
        }
    }

    private static void getContractDuration(HttpExchange exchange)
            throws IOException {

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "GET method required."));
            return;
        }

        try {
            String query = exchange.getRequestURI().getQuery();

            if (query == null || !query.contains("patentId=")
                    || !query.contains("ownerId=")) {
                sendJson(exchange, 400,
                        Map.of("error", "Provide patentId and ownerId."));
                return;
            }

            Map<String, String> params = new HashMap<>();

            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2) {
                    params.put(pair[0], pair[1]);
                }
            }

            int patentId = Integer.parseInt(params.get("patentId"));
            int ownerId = Integer.parseInt(params.get("ownerId"));

            String sql = "SELECT contract_duration(?, ?) AS DurationDays";

            try (Connection con = getConnection();
                    PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, patentId);
                ps.setInt(2, ownerId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("patentId", patentId);
                        result.put("ownerId", ownerId);
                        result.put("durationDays",
                                rs.getObject("DurationDays"));

                        sendJson(exchange, 200, result);
                    } else {
                        sendJson(exchange, 404,
                                Map.of("error", "Contract duration not found."));
                    }
                }
            }

        } catch (NumberFormatException e) {
            sendJson(exchange, 400, Map.of("error", "IDs must be numbers."));
        } catch (SQLException e) {
            e.printStackTrace();

            sendJson(exchange, 500,
                    Map.of("error", e.getMessage()));
        }
    }

}
