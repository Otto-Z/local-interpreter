//! Course material (PDF slides / Markdown / TXT) chunk storage and bilingual keyword/bigram retrieval.

use core_model::{new_id, now_ms};
use rusqlite::params;
use serde::{Deserialize, Serialize};

use crate::{Result, Store};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CourseMaterial {
    pub id: String,
    pub course_id: String,
    pub name: String,
    pub path: String,
    pub page_count: u32,
    pub created_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MaterialHit {
    pub material_id: String,
    pub material_name: String,
    pub page: u32,
    pub text: String,
    pub score: f64,
}

impl Store {
    /// Insert a course material along with its extracted per-page text chunks `(page_num, text)`.
    pub fn add_material_pages(
        &self,
        course_id: &str,
        name: &str,
        path: &str,
        pages: &[(u32, String)],
    ) -> Result<CourseMaterial> {
        let id = new_id();
        let ts = now_ms();
        let clean_pages: Vec<(u32, String)> = pages
            .iter()
            .filter_map(|(p, t)| {
                let s = t.trim();
                if s.is_empty() {
                    None
                } else {
                    Some((*p, s.to_string()))
                }
            })
            .collect();
        let page_count = clean_pages.len() as u32;

        let mut conn = self.lock()?;
        let tx = conn.transaction()?;
        tx.execute(
            "INSERT INTO materials(id, course_id, name, path, page_count, created_at)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
            params![id, course_id, name.trim(), path, page_count, ts],
        )?;
        for (page, text) in &clean_pages {
            tx.execute(
                "INSERT INTO material_chunks(id, material_id, course_id, page, text)
                 VALUES(?1, ?2, ?3, ?4, ?5)",
                params![new_id(), id, course_id, *page, text],
            )?;
        }
        tx.commit()?;

        Ok(CourseMaterial {
            id,
            course_id: course_id.to_string(),
            name: name.trim().to_string(),
            path: path.to_string(),
            page_count,
            created_at: ts,
        })
    }

    pub fn list_materials(&self, course_id: &str) -> Result<Vec<CourseMaterial>> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT id, course_id, name, path, page_count, created_at
             FROM materials WHERE course_id = ?1 ORDER BY created_at DESC",
        )?;
        let rows = stmt.query_map([course_id], |r| {
            Ok(CourseMaterial {
                id: r.get(0)?,
                course_id: r.get(1)?,
                name: r.get(2)?,
                path: r.get(3)?,
                page_count: r.get(4)?,
                created_at: r.get(5)?,
            })
        })?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    pub fn delete_material(&self, material_id: &str) -> Result<()> {
        let mut conn = self.lock()?;
        let tx = conn.transaction()?;
        tx.execute("DELETE FROM material_chunks WHERE material_id = ?1", [material_id])?;
        tx.execute("DELETE FROM materials WHERE id = ?1", [material_id])?;
        tx.commit()?;
        Ok(())
    }

    /// Retrieve top relevant slide/document chunks for a course using bilingual English tokens + CJK bigrams.
    pub fn search_material_chunks(
        &self,
        course_id: &str,
        query: &str,
        limit: usize,
    ) -> Result<Vec<MaterialHit>> {
        let terms = extract_query_terms(query);
        if terms.is_empty() || limit == 0 {
            return Ok(Vec::new());
        }

        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT c.material_id, m.name, c.page, c.text
             FROM material_chunks c
             JOIN materials m ON m.id = c.material_id
             WHERE c.course_id = ?1",
        )?;
        let mut scored: Vec<MaterialHit> = Vec::new();
        let rows = stmt.query_map([course_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, u32>(2)?,
                r.get::<_, String>(3)?,
            ))
        })?;

        for row in rows {
            let (material_id, material_name, page, text) = row?;
            let lower = text.to_lowercase();
            let mut score = 0.0f64;
            for term in &terms {
                let matches = lower.match_indices(term.as_str()).count();
                if matches > 0 {
                    // Weight longer technical terms higher while applying sublinear TF saturation.
                    let weight = (term.chars().count().min(8) as f64) / 2.0;
                    score += (1.0 + (matches as f64).ln()) * weight;
                }
            }
            if score > 0.0 {
                scored.push(MaterialHit {
                    material_id,
                    material_name,
                    page,
                    text,
                    score,
                });
            }
        }

        scored.sort_by(|a, b| b.score.total_cmp(&a.score));
        scored.truncate(limit);
        Ok(scored)
    }

    /// Return first `max_chars` of all course material chunks (used to build pre-class study outlines).
    pub fn sample_course_material_text(&self, course_id: &str, max_chars: usize) -> Result<String> {
        let conn = self.lock()?;
        let mut stmt = conn.prepare(
            "SELECT m.name, c.page, c.text
             FROM material_chunks c
             JOIN materials m ON m.id = c.material_id
             WHERE c.course_id = ?1
             ORDER BY m.created_at DESC, c.page ASC",
        )?;
        let rows = stmt.query_map([course_id], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, u32>(1)?, r.get::<_, String>(2)?))
        })?;
        let mut out = String::new();
        for row in rows {
            let (name, page, text) = row?;
            let block = format!("[{name} 第{page}页]\n{}\n\n", text.trim());
            if out.chars().count() + block.chars().count() > max_chars {
                break;
            }
            out.push_str(&block);
        }
        Ok(out)
    }
}

fn extract_query_terms(query: &str) -> Vec<String> {
    const STOP: &[&str] = &[
        "的", "了", "和", "是", "在", "有", "与", "及", "就", "也", "都", "这", "那", "什么",
        "怎么", "为什么", "如何", "请问", "the", "and", "for", "with", "that", "this", "what",
        "how", "why", "are", "from",
    ];
    let mut terms: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();

    // 1. ASCII/alphanumeric words (>= 2 chars)
    let mut ascii_buf = String::new();
    let mut cjk_buf: Vec<char> = Vec::new();
    let flush_ascii = |buf: &mut String,
                       terms: &mut Vec<String>,
                       seen: &mut std::collections::HashSet<String>| {
        if buf.len() >= 2 {
            let low = buf.to_ascii_lowercase();
            if !STOP.contains(&low.as_str()) && seen.insert(low.clone()) {
                terms.push(low);
            }
        }
        buf.clear();
    };
    let flush_cjk = |buf: &mut Vec<char>,
                     terms: &mut Vec<String>,
                     seen: &mut std::collections::HashSet<String>| {
        if buf.len() >= 2 {
            for win in buf.windows(2) {
                let bi: String = win.iter().collect();
                if !STOP.contains(&bi.as_str()) && seen.insert(bi.clone()) {
                    terms.push(bi);
                }
            }
        }
        buf.clear();
    };

    for ch in query.chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' {
            flush_cjk(&mut cjk_buf, &mut terms, &mut seen);
            ascii_buf.push(ch);
        } else if ('\u{4E00}'..='\u{9FFF}').contains(&ch) {
            flush_ascii(&mut ascii_buf, &mut terms, &mut seen);
            cjk_buf.push(ch);
        } else {
            flush_ascii(&mut ascii_buf, &mut terms, &mut seen);
            flush_cjk(&mut cjk_buf, &mut terms, &mut seen);
        }
    }
    flush_ascii(&mut ascii_buf, &mut terms, &mut seen);
    flush_cjk(&mut cjk_buf, &mut terms, &mut seen);

    terms.truncate(24);
    terms
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn course_material_storage_and_bilingual_retrieval() {
        let store = Store::open_in_memory("mac-mat").unwrap();
        let c = store.create_course("CS224N", "课堂").unwrap();
        let mat = store
            .add_material_pages(
                &c.id,
                "Lecture05_Attention.pdf",
                "/tmp/lec5.pdf",
                &[
                    (1, "Course logistics and syllabus overview.".into()),
                    (
                        2,
                        "Scaled dot-product attention divides QK^T by sqrt(d_k) to prevent gradient vanishing."
                            .into(),
                    ),
                    (3, "位置编码（Positional Encoding）为 Transformer 注入词序信息。".into()),
                ],
            )
            .unwrap();
        assert_eq!(mat.page_count, 3);
        assert_eq!(store.list_materials(&c.id).unwrap().len(), 1);

        let hits_en = store
            .search_material_chunks(&c.id, "Why divide QK by sqrt d_k attention?", 2)
            .unwrap();
        assert_eq!(hits_en[0].page, 2);

        let hits_zh = store
            .search_material_chunks(&c.id, "位置编码有什么作用？", 2)
            .unwrap();
        assert_eq!(hits_zh[0].page, 3);

        store.delete_material(&mat.id).unwrap();
        assert!(store.list_materials(&c.id).unwrap().is_empty());
    }
}
