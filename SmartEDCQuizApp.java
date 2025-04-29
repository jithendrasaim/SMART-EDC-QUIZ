import javax.swing.*;
import java.awt.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class SmartEDCQuizApp {
    private static final String DB_URL = "jdbc:oracle:thin:@localhost:1521:XE";
    private static final String DB_USER = "SYSTEM";
    private static final String DB_PASSWORD = "12345678";

    static class Quiz {
        int id;
        String title;

        Quiz(int id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    static class Question {
        int id;
        String text;
        String answer;

        Question(int id, String text, String answer) {
            this.id = id;
            this.text = text;
            this.answer = answer;
        }
    }

    static class ResultDetail {
        int resultId;
        int attemptNumber;
        int marks;
        List<QuestionResult> questionResults;

        ResultDetail(int resultId, int attemptNumber, int marks, List<QuestionResult> questionResults) {
            this.resultId = resultId;
            this.attemptNumber = attemptNumber;
            this.marks = marks;
            this.questionResults = questionResults;
        }
    }

    static class QuestionResult {
        int questionId;
        String questionText;
        String userAnswer;
        String correctAnswer;
        boolean isCorrect;

        QuestionResult(int questionId, String questionText, String userAnswer, String correctAnswer, boolean isCorrect) {
            this.questionId = questionId;
            this.questionText = questionText;
            this.userAnswer = userAnswer;
            this.correctAnswer = correctAnswer;
            this.isCorrect = isCorrect;
        }
    }

    static class UserResult {
        String quizTitle;
        int attemptNumber;
        int marks;
        int totalQuestions;

        UserResult(String quizTitle, int attemptNumber, int marks, int totalQuestions) {
            this.quizTitle = quizTitle;
            this.attemptNumber = attemptNumber;
            this.marks = marks;
            this.totalQuestions = totalQuestions;
        }
    }

    private static int authenticateUser(String email, String password) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement("SELECT user_id FROM Users WHERE email = ? AND password = ?")) {
            pstmt.setString(1, email);
            pstmt.setString(2, password);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("user_id");
            }
            return -1;
        }
    }

    private static List<Quiz> getQuizzes() throws SQLException {
        List<Quiz> quizzes = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT quiz_id, title FROM Quizzes")) {
            while (rs.next()) {
                quizzes.add(new Quiz(rs.getInt("quiz_id"), rs.getString("title")));
            }
        }
        return quizzes;
    }

    private static List<Question> getQuestions(int quizId) throws SQLException {
        List<Question> questions = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT question_id, question, answer FROM Questions WHERE quiz_id = ?")) {
            pstmt.setInt(1, quizId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                questions.add(new Question(
                        rs.getInt("question_id"),
                        rs.getString("question"),
                        rs.getString("answer")
                ));
            }
        }
        return questions;
    }

    private static int saveResults(int userId, int quizId, int marks, List<Integer> questionIds, List<String> userAnswers) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            int attemptNumber = 1;
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT MAX(attempt_number) AS max_attempt FROM Results WHERE user_id = ? AND quiz_id = ?")) {
                pstmt.setInt(1, userId);
                pstmt.setInt(2, quizId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next() && rs.getInt("max_attempt") > 0) {
                    attemptNumber = rs.getInt("max_attempt") + 1;
                }
            }

            int resultId;
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO Results (result_id, user_id, quiz_id, attempt_number, marks) VALUES (?, ?, ?, ?, ?)")) {
                resultId = getNextResultId(conn);
                pstmt.setInt(1, resultId);
                pstmt.setInt(2, userId);
                pstmt.setInt(3, quizId);
                pstmt.setInt(4, attemptNumber);
                pstmt.setInt(5, marks);
                pstmt.executeUpdate();
            }

            try (PreparedStatement pstmt = conn.prepareStatement(
                    "INSERT INTO Marked (ques_id, res_id) VALUES (?, ?)")) {
                for (int quesId : questionIds) {
                    pstmt.setInt(1, quesId);
                    pstmt.setInt(2, resultId);
                    pstmt.executeUpdate();
                }
            }

            return resultId;
        }
    }

    private static int getNextResultId(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(result_id) AS max_id FROM Results")) {
            if (rs.next()) {
                return rs.getInt("max_id") + 1;
            }
            return 123;
        }
    }

    private static ResultDetail getResultDetails(int userId, int quizId, int resultId, List<String> userAnswers) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD)) {
            int attemptNumber = 0;
            int marks = 0;
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT attempt_number, marks FROM Results WHERE result_id = ? AND user_id = ? AND quiz_id = ?")) {
                pstmt.setInt(1, resultId);
                pstmt.setInt(2, userId);
                pstmt.setInt(3, quizId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    attemptNumber = rs.getInt("attempt_number");
                    marks = rs.getInt("marks");
                }
            }

            List<QuestionResult> questionResults = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT q.question_id, q.question, q.answer, m.ques_id " +
                            "FROM Questions q JOIN Marked m ON q.question_id = m.ques_id " +
                            "WHERE m.res_id = ? ORDER BY q.question_id")) {
                pstmt.setInt(1, resultId);
                ResultSet rs = pstmt.executeQuery();
                int index = 0;
                while (rs.next()) {
                    int questionId = rs.getInt("question_id");
                    String questionText = rs.getString("question");
                    String correctAnswer = rs.getString("answer");
                    String userAnswer = index < userAnswers.size() ? userAnswers.get(index) : "";
                    boolean isCorrect = userAnswer.equalsIgnoreCase(correctAnswer);
                    questionResults.add(new QuestionResult(questionId, questionText, userAnswer, correctAnswer, isCorrect));
                    index++;
                }
            }

            return new ResultDetail(resultId, attemptNumber, marks, questionResults);
        }
    }

    private static List<UserResult> getAllUserResults(int userId) throws SQLException {
        List<UserResult> results = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT q.title, r.attempt_number, r.marks, COUNT(m.ques_id) AS total_questions " +
                             "FROM Results r " +
                             "JOIN Quizzes q ON r.quiz_id = q.quiz_id " +
                             "LEFT JOIN Marked m ON r.result_id = m.res_id " +
                             "WHERE r.user_id = ? " +
                             "GROUP BY q.title, r.attempt_number, r.marks " +
                             "ORDER BY q.title, r.attempt_number")) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                results.add(new UserResult(
                        rs.getString("title"),
                        rs.getInt("attempt_number"),
                        rs.getInt("marks"),
                        rs.getInt("total_questions")
                ));
            }
        }
        return results;
    }

    private static String[] getRandomDistractors(String correctAnswer, String[] pool, int count) {
        List<String> available = new ArrayList<>(Arrays.asList(pool));
        available.removeIf(s -> s.equalsIgnoreCase(correctAnswer));
        Random random = new Random();
        String[] distractors = new String[count];
        for (int i = 0; i < count && !available.isEmpty(); i++) {
            int index = random.nextInt(available.size());
            distractors[i] = available.remove(index);
        }
        for (int i = 0; i < count; i++) {
            if (distractors[i] == null) {
                distractors[i] = "Alternate " + (i + 1);
            }
        }
        return distractors;
    }

    static class LoginFrame extends JFrame {
        private JTextField emailField;
        private JPasswordField passwordField;
        private JButton loginButton;

        public LoginFrame() {
            setTitle("Smart EDC Quiz - Login");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(400, 200);
            setLocationRelativeTo(null);
            setLayout(new GridLayout(3, 2, 10, 10));

            add(new JLabel("Email:"));
            emailField = new JTextField();
            add(emailField);

            add(new JLabel("Password:"));
            passwordField = new JPasswordField();
            add(passwordField);

            loginButton = new JButton("Login");
            loginButton.addActionListener(e -> handleLogin());
            add(new JLabel(""));
            add(loginButton);

            setVisible(true);
        }

        private void handleLogin() {
            String email = emailField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();

            try {
                int userId = authenticateUser(email, password);
                if (userId != -1) {
                    JOptionPane.showMessageDialog(this, "Login successful!", "Success", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                    new QuizSelectionFrame(userId);
                } else {
                    JOptionPane.showMessageDialog(this, "Invalid email or password.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Database error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    static class QuizSelectionFrame extends JFrame {
        private JComboBox<Quiz> quizComboBox;
        private JButton startButton;
        private JButton viewResultsButton;
        private int userId;

        public QuizSelectionFrame(int userId) {
            this.userId = userId;
            setTitle("Smart EDC Quiz - Select Quiz");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(400, 250);
            setLocationRelativeTo(null);
            setLayout(new GridLayout(4, 1, 10, 10));

            add(new JLabel("Select a Quiz:"));
            quizComboBox = new JComboBox<>();
            add(quizComboBox);

            startButton = new JButton("Start Quiz");
            startButton.addActionListener(e -> startQuiz());
            add(startButton);

            viewResultsButton = new JButton("View All Results");
            viewResultsButton.addActionListener(e -> viewAllResults());
            add(viewResultsButton);

            loadQuizzes();
            setVisible(true);
        }

        private void loadQuizzes() {
            try {
                List<Quiz> quizzes = getQuizzes();
                for (Quiz quiz : quizzes) {
                    quizComboBox.addItem(quiz);
                }
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Error loading quizzes: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void startQuiz() {
            Quiz selectedQuiz = (Quiz) quizComboBox.getSelectedItem();
            if (selectedQuiz != null) {
                dispose();
                new QuestionFrame(userId, selectedQuiz.id);
            } else {
                JOptionPane.showMessageDialog(this, "Please select a quiz.", "Warning", JOptionPane.WARNING_MESSAGE);
            }
        }

        private void viewAllResults() {
            dispose();
            new AllResultsFrame(userId);
        }
    }

    static class QuestionFrame extends JFrame {
        private int userId;
        private int quizId;
        private List<Question> questions;
        private List<String> userAnswers;
        private JButton submitButton;
        private int currentQuestionIndex;
        private ButtonGroup optionGroup;
        private JRadioButton optionA;
        private JRadioButton optionB;
        private JRadioButton optionC;
        private JRadioButton optionD;

        public QuestionFrame(int userId, int quizId) {
            this.userId = userId;
            this.quizId = quizId;
            this.currentQuestionIndex = 0;
            this.userAnswers = new ArrayList<>();
            this.optionGroup = new ButtonGroup();

            setTitle("Smart EDC Quiz - Questions");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(600, 400);
            setLocationRelativeTo(null);
            setLayout(new BorderLayout());

            loadQuestions();
            if (!questions.isEmpty()) {
                displayQuestion();
            } else {
                JOptionPane.showMessageDialog(this, "No questions available for this quiz.", "Error", JOptionPane.ERROR_MESSAGE);
                dispose();
                new QuizSelectionFrame(userId);
            }
        }

        private void loadQuestions() {
            try {
                questions = getQuestions(quizId);
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Error loading questions: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                dispose();
                new QuizSelectionFrame(userId);
            }
        }

        private void displayQuestion() {
            getContentPane().removeAll();
            if (optionGroup.getSelection() != null) {
                String selectedAnswer = getSelectedOptionText();
                userAnswers.add(selectedAnswer);
            }

            JPanel questionPanel = new JPanel(new GridLayout(6, 1, 10, 10));
            Question currentQuestion = questions.get(currentQuestionIndex);
            JLabel questionLabel = new JLabel((currentQuestionIndex + 1) + ". " + currentQuestion.text);
            questionPanel.add(questionLabel);

            String[] options = generateOptions(currentQuestion.answer, quizId);
            optionA = new JRadioButton("A. " + options[0]);
            optionB = new JRadioButton("B. " + options[1]);
            optionC = new JRadioButton("C. " + options[2]);
            optionD = new JRadioButton("D. " + options[3]);

            optionGroup = new ButtonGroup();
            optionGroup.add(optionA);
            optionGroup.add(optionB);
            optionGroup.add(optionC);
            optionGroup.add(optionD);

            questionPanel.add(optionA);
            questionPanel.add(optionB);
            questionPanel.add(optionC);
            questionPanel.add(optionD);

            add(questionPanel, BorderLayout.CENTER);

            JPanel buttonPanel = new JPanel();
            submitButton = new JButton(currentQuestionIndex < questions.size() - 1 ? "Next" : "Submit");
            submitButton.addActionListener(e -> handleAnswer());
            buttonPanel.add(submitButton);
            add(buttonPanel, BorderLayout.SOUTH);

            revalidate();
            repaint();
            setVisible(true);
        }

        private String[] generateOptions(String correctAnswer, int quizId) {
            String[] options = new String[4];
            options[0] = correctAnswer;

            if (correctAnswer.equalsIgnoreCase("Paris")) {
                options[1] = "London";
                options[2] = "Berlin";
                options[3] = "Madrid";
                return options;
            } else if (correctAnswer.equalsIgnoreCase("7")) {
                options[1] = "5";
                options[2] = "6";
                options[3] = "8";
                return options;
            }

            String[] cityPool = {"Tokyo", "New York", "Sydney", "Rome", "Cairo", "Moscow", "Beijing"};
            String[] numberPool = {"4", "10", "12", "3", "9", "15", "20"};
            String[] programmingPool = {"Tuple", "Dictionary", "String", "Integer", "Set", "Array", "Function"};

            String[] selectedPool;
            if (quizId == 1600 || quizId == 1602) {
                selectedPool = isNumeric(correctAnswer) ? numberPool : cityPool;
            } else {
                selectedPool = programmingPool;
            }

            String[] distractors = getRandomDistractors(correctAnswer, selectedPool, 3);
            options[1] = distractors[0];
            options[2] = distractors[1];
            options[3] = distractors[2];

            return options;
        }

        private boolean isNumeric(String str) {
            try {
                Integer.parseInt(str);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        private String getSelectedOptionText() {
            if (optionA.isSelected()) return optionA.getText().substring(3);
            if (optionB.isSelected()) return optionB.getText().substring(3);
            if (optionC.isSelected()) return optionC.getText().substring(3);
            if (optionD.isSelected()) return optionD.getText().substring(3);
            return "";
        }

        private void handleAnswer() {
            if (currentQuestionIndex < questions.size() - 1) {
                currentQuestionIndex++;
                displayQuestion();
            } else {
                submitQuiz();
            }
        }

        private void submitQuiz() {
            String selectedAnswer = getSelectedOptionText();
            userAnswers.add(selectedAnswer);

            int marks = 0;
            List<Integer> questionIds = new ArrayList<>();
            for (int i = 0; i < questions.size(); i++) {
                Question q = questions.get(i);
                String userAnswer = userAnswers.get(i);
                questionIds.add(q.id);
                if (userAnswer.equalsIgnoreCase(q.answer)) {
                    marks++;
                }
            }

            try {
                int resultId = saveResults(userId, quizId, marks, questionIds, userAnswers);
                ResultDetail result = getResultDetails(userId, quizId, resultId, userAnswers);
                JOptionPane.showMessageDialog(this, "Quiz submitted! Your score: " + marks + "/" + questions.size(), "Results", JOptionPane.INFORMATION_MESSAGE);
                dispose();
                new ResultFrame(userId, quizId, result);
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Error saving results: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    static class ResultFrame extends JFrame {
        public ResultFrame(int userId, int quizId, ResultDetail result) {
            setTitle("Smart EDC Quiz - Results");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(600, 400);
            setLocationRelativeTo(null);
            setLayout(new BorderLayout());

            JPanel summaryPanel = new JPanel(new GridLayout(3, 1));
            summaryPanel.add(new JLabel("Quiz Completed!", SwingConstants.CENTER));
            summaryPanel.add(new JLabel("Attempt Number: " + result.attemptNumber, SwingConstants.CENTER));
            summaryPanel.add(new JLabel("Your Score: " + result.marks + "/" + result.questionResults.size(), SwingConstants.CENTER));
            add(summaryPanel, BorderLayout.NORTH);

            String[] columns = {"Question", "Your Answer", "Correct Answer", "Status"};
            Object[][] data = new Object[result.questionResults.size()][4];
            for (int i = 0; i < result.questionResults.size(); i++) {
                QuestionResult qr = result.questionResults.get(i);
                data[i][0] = qr.questionText;
                data[i][1] = qr.userAnswer.isEmpty() ? "Not Answered" : qr.userAnswer;
                data[i][2] = qr.correctAnswer;
                data[i][3] = qr.isCorrect ? "Correct" : "Incorrect";
            }
            JTable resultTable = new JTable(data, columns);
            JScrollPane scrollPane = new JScrollPane(resultTable);
            add(scrollPane, BorderLayout.CENTER);

            JButton backButton = new JButton("Back to Quizzes");
            backButton.addActionListener(e -> {
                dispose();
                new QuizSelectionFrame(userId);
            });
            JPanel buttonPanel = new JPanel();
            buttonPanel.add(backButton);
            add(buttonPanel, BorderLayout.SOUTH);

            setVisible(true);
        }
    }

    static class AllResultsFrame extends JFrame {
        public AllResultsFrame(int userId) {
            setTitle("Smart EDC Quiz - All Results");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(600, 400);
            setLocationRelativeTo(null);
            setLayout(new BorderLayout());

            List<UserResult> results;
            try {
                results = getAllUserResults(userId);
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(this, "Error loading results: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                results = new ArrayList<>();
            }

            String[] columns = {"Quiz Title", "Attempt Number", "Score"};
            Object[][] data = new Object[results.size()][3];
            for (int i = 0; i < results.size(); i++) {
                UserResult ur = results.get(i);
                data[i][0] = ur.quizTitle;
                data[i][1] = ur.attemptNumber;
                data[i][2] = ur.marks + "/" + ur.totalQuestions;
            }
            JTable resultTable = new JTable(data, columns);
            JScrollPane scrollPane = new JScrollPane(resultTable);
            add(scrollPane, BorderLayout.CENTER);

            JButton backButton = new JButton("Back to Quizzes");
            backButton.addActionListener(e -> {
                dispose();
                new QuizSelectionFrame(userId);
            });
            JPanel buttonPanel = new JPanel();
            buttonPanel.add(backButton);
            add(buttonPanel, BorderLayout.SOUTH);

            setVisible(true);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LoginFrame());
    }
}