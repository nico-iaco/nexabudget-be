package it.iacovelli.nexabudgetbe;

import it.iacovelli.nexabudgetbe.dto.AccountDto;
import it.iacovelli.nexabudgetbe.model.Account;
import it.iacovelli.nexabudgetbe.model.AccountType;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.repository.AccountRepository;
import it.iacovelli.nexabudgetbe.repository.CategoryRepository;
import it.iacovelli.nexabudgetbe.repository.TransactionRepository;
import it.iacovelli.nexabudgetbe.repository.UserRepository;
import it.iacovelli.nexabudgetbe.service.AccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Ordine corretto di pulizia
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .username("testuser")
                .email("test@example.com")
                .passwordHash("hashedPassword")
                .build();
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testCreateAccount_WithoutStarterBalance() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("Conto Corrente");
        request.setType(AccountType.CONTO_CORRENTE);
        request.setCurrency("EUR");

        AccountDto.AccountResponse response = accountService.createAccount(request, null, testUser.getId());

        assertNotNull(response);
        assertEquals("Conto Corrente", response.getName());
        assertEquals(AccountType.CONTO_CORRENTE, response.getType());
        assertEquals("EUR", response.getCurrency());
        assertEquals(BigDecimal.ZERO, response.getActualBalance());
    }

    @Test
    void testCreateAccount_WithPositiveStarterBalance() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("Risparmio");
        request.setType(AccountType.RISPARMIO);
        request.setCurrency("EUR");

        AccountDto.AccountResponse response = accountService.createAccount(
                request, new BigDecimal("1000"), testUser.getId()
        );

        assertNotNull(response);
        assertEquals("Risparmio", response.getName());
        assertEquals(0, new BigDecimal("1000").compareTo(response.getActualBalance()));
    }


    @Test
    void testCreateAccount_WithNegativeStarterBalance() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("Credito");
        request.setType(AccountType.CONTO_CORRENTE);
        request.setCurrency("EUR");

        AccountDto.AccountResponse response = accountService.createAccount(
                request, new BigDecimal("-500"), testUser.getId()
        );

        assertNotNull(response);
        assertEquals(0, new BigDecimal("-500").compareTo(response.getActualBalance()));
    }

    @Test
    void testCreateAccount_UserNotFound() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("Test");
        request.setType(AccountType.CONTO_CORRENTE);
        request.setCurrency("EUR");

        assertThrows(ResponseStatusException.class, () ->
                accountService.createAccount(request, null, UUID.randomUUID())
        );
    }

    @Test
    void testGetAccountById() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("Test Account");
        request.setType(AccountType.CONTO_CORRENTE);
        request.setCurrency("EUR");

        AccountDto.AccountResponse created = accountService.createAccount(request, null, testUser.getId());

        Optional<Account> found = accountService.getAccountById(created.getId());

        assertTrue(found.isPresent());
        assertEquals("Test Account", found.get().getName());
    }

    @Test
    void testGetAccountByIdAndUser() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("My Account");
        request.setType(AccountType.RISPARMIO);
        request.setCurrency("USD");

        AccountDto.AccountResponse created = accountService.createAccount(request, null, testUser.getId());

        Optional<AccountDto.AccountResponse> found = accountService.getAccountByIdAndUser(
                created.getId(), testUser
        );

        assertTrue(found.isPresent());
        assertEquals("My Account", found.get().getName());
        assertEquals("USD", found.get().getCurrency());
    }

    @Test
    void testGetAccountsByUser() {
        AccountDto.AccountRequest request1 = new AccountDto.AccountRequest();
        request1.setName("Account 1");
        request1.setType(AccountType.RISPARMIO);
        request1.setCurrency("EUR");

        AccountDto.AccountRequest request2 = new AccountDto.AccountRequest();
        request2.setName("Account 2");
        request2.setType(AccountType.RISPARMIO);
        request2.setCurrency("EUR");

        accountService.createAccount(request1, null, testUser.getId());
        accountService.createAccount(request2, null, testUser.getId());

        List<AccountDto.AccountResponse> accounts = accountService.getAccountsByUser(testUser);

        assertEquals(2, accounts.size());
    }

    @Test
    void testUpdateAccount() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("Old Name");
        request.setType(AccountType.CONTO_CORRENTE);
        request.setCurrency("EUR");

        AccountDto.AccountResponse created = accountService.createAccount(request, null, testUser.getId());

        AccountDto.AccountResponse updated = accountService.updateAccount(
                created.getId(), testUser, "New Name", AccountType.RISPARMIO, "USD"
        );

        assertEquals("New Name", updated.getName());
        assertEquals(AccountType.RISPARMIO, updated.getType());
        assertEquals("USD", updated.getCurrency());
    }

    @Test
    void testUpdateAccount_NotFound() {
        assertThrows(ResponseStatusException.class, () ->
                accountService.updateAccount(UUID.randomUUID(), testUser, "Name", AccountType.CONTO_CORRENTE, "EUR")
        );
    }

    @Test
    void testDeleteAccount() {
        AccountDto.AccountRequest request = new AccountDto.AccountRequest();
        request.setName("To Delete");
        request.setType(AccountType.CONTO_CORRENTE);
        request.setCurrency("EUR");

        AccountDto.AccountResponse created = accountService.createAccount(request, null, testUser.getId());

        accountService.deleteAccount(created.getId());

        Optional<Account> found = accountService.getAccountById(created.getId());
        assertFalse(found.isPresent());
    }

    @Test
    void testGetTotalBalance() {
        AccountDto.AccountRequest request1 = new AccountDto.AccountRequest();
        request1.setName("Account 1");
        request1.setType(AccountType.CONTO_CORRENTE);
        request1.setCurrency("EUR");

        AccountDto.AccountRequest request2 = new AccountDto.AccountRequest();
        request2.setName("Account 2");
        request2.setType(AccountType.RISPARMIO);
        request2.setCurrency("EUR");

        accountService.createAccount(request1, new BigDecimal("100"), testUser.getId());
        accountService.createAccount(request2, new BigDecimal("200"), testUser.getId());

        BigDecimal total = accountService.getTotalBalance(testUser, "EUR");

        assertEquals(0, new BigDecimal("300").compareTo(total));
    }
}
