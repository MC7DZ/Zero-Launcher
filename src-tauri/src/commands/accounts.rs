use tauri::State;
use crate::models::AccountInfo;
use crate::state::AppState;

/// Add a new offline account.
#[tauri::command]
pub async fn add_offline_account(
    state: State<'_, AppState>,
    username: String,
) -> Result<AccountInfo, String> {
    if username.trim().is_empty() {
        return Err("Username cannot be empty".to_string());
    }
    if username.len() > 16 {
        return Err("Username must be 16 characters or less".to_string());
    }

    let account = AccountInfo {
        id: uuid::Uuid::new_v4().to_string(),
        username: username.trim().to_string(),
        account_type: "offline".to_string(),
        is_active: false,
    };

    let result = {
        let mut accounts = state.accounts.lock().unwrap();
        // If this is the first account, make it active
        if accounts.is_empty() {
            let mut acc = account.clone();
            acc.is_active = true;
            accounts.push(acc.clone());
            acc
        } else {
            accounts.push(account.clone());
            account
        }
    };
    state.save_accounts();

    Ok(result)
}

/// Remove an account by ID.
#[tauri::command]
pub async fn remove_account(
    state: State<'_, AppState>,
    id: String,
) -> Result<(), String> {
    {
        let mut accounts = state.accounts.lock().unwrap();
        let was_active = accounts.iter().find(|a| a.id == id).map(|a| a.is_active).unwrap_or(false);
        accounts.retain(|a| a.id != id);

        // If we removed the active account, activate the first remaining one
        if was_active && !accounts.is_empty() {
            accounts[0].is_active = true;
        }
    }
    state.save_accounts();
    Ok(())
}

/// Get all accounts.
#[tauri::command]
pub async fn list_accounts(
    state: State<'_, AppState>,
) -> Result<Vec<AccountInfo>, String> {
    let accounts = state.accounts.lock().unwrap().clone();
    Ok(accounts)
}

/// Set an account as the active one.
#[tauri::command]
pub async fn set_active_account(
    state: State<'_, AppState>,
    id: String,
) -> Result<(), String> {
    {
        let mut accounts = state.accounts.lock().unwrap();
        for account in accounts.iter_mut() {
            account.is_active = account.id == id;
        }
    }
    state.save_accounts();
    Ok(())
}
