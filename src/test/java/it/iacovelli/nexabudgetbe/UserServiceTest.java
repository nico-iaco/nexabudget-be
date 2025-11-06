package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    @Test
    void testCreateUser() {
        User user = User.builder()
                .username("newuser")
                .email("newuser@example.com")
                .passwordHash("rawPassword")
                .build();

        User created = userService.createUser(user);

        assertNotNull(created.getId());
        assertEquals("newuser", created.getUsername());
        assertNotEquals("rawPassword", created.getPasswordHash()); // Password deve essere codificata
    }

    @Test
    void testCreateUser_DuplicateUsername() {
        User user1 = User.builder()
                .username("duplicate")
                .email("user1@example.com")
                .passwordHash("password")
                .build();

        userService.createUser(user1);

        User user2 = User.builder()
                .username("duplicate")
                .email("user2@example.com")
                .passwordHash("password")
                .build();

        assertThrows(IllegalArgumentException.class, () -> userService.createUser(user2));
    }

    @Test
    void testCreateUser_DuplicateEmail() {
        User user1 = User.builder()
                .username("user1")
                .email("duplicate@example.com")
                .passwordHash("password")
                .build();

        userService.createUser(user1);

        User user2 = User.builder()
                .username("user2")
                .email("duplicate@example.com")
                .passwordHash("password")
                .build();

        assertThrows(IllegalArgumentException.class, () -> userService.createUser(user2));
    }

    @Test
    void testGetUserById() {
        User user = User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("password")
                .build();

        User created = userService.createUser(user);

        Optional<User> found = userService.getUserById(created.getId());

        assertTrue(found.isPresent());
        assertEquals("testuser", found.get().getUsername());
    }

    @Test
    void testGetUserByUsername() {
        User user = User.builder()
                .username("findme")
                .email("findme@example.com")
                .passwordHash("password")
                .build();

        userService.createUser(user);

        Optional<User> found = userService.getUserByUsername("findme");

        assertTrue(found.isPresent());
        assertEquals("findme@example.com", found.get().getEmail());
    }

    @Test
    void testGetUserByEmail() {
        User user = User.builder()
                .username("emailuser")
                .email("email@example.com")
                .passwordHash("password")
                .build();

        userService.createUser(user);

        Optional<User> found = userService.getUserByEmail("email@example.com");

        assertTrue(found.isPresent());
        assertEquals("emailuser", found.get().getUsername());
    }

    @Test
    void testGetAllUsers() {
        User user1 = User.builder()
                .username("user1")
                .email("user1@example.com")
                .passwordHash("password")
                .build();

        User user2 = User.builder()
                .username("user2")
                .email("user2@example.com")
                .passwordHash("password")
                .build();

        userService.createUser(user1);
        userService.createUser(user2);

        List<User> users = userService.getAllUsers();

        assertEquals(2, users.size());
    }

    @Test
    void testUpdateUser() {
        User user = User.builder()
                .username("oldname")
                .email("old@example.com")
                .passwordHash("password")
                .build();

        User created = userService.createUser(user);
        created.setUsername("newname");

        User updated = userService.updateUser(created);

        assertEquals("newname", updated.getUsername());
    }

    @Test
    void testDeleteUser() {
        User user = User.builder()
                .username("todelete")
                .email("delete@example.com")
                .passwordHash("password")
                .build();

        User created = userService.createUser(user);

        userService.deleteUser(created.getId());

        Optional<User> found = userService.getUserById(created.getId());
        assertFalse(found.isPresent());
    }

    @Test
    void testVerifyPassword_Correct() {
        User user = User.builder()
                .username("passtest")
                .email("pass@example.com")
                .passwordHash("mypassword")
                .build();

        User created = userService.createUser(user);

        boolean isValid = userService.verifyPassword(created, "mypassword");

        assertTrue(isValid);
    }

    @Test
    void testVerifyPassword_Incorrect() {
        User user = User.builder()
                .username("passtest2")
                .email("pass2@example.com")
                .passwordHash("correctpassword")
                .build();

        User created = userService.createUser(user);

        boolean isValid = userService.verifyPassword(created, "wrongpassword");

        assertFalse(isValid);
    }
}
