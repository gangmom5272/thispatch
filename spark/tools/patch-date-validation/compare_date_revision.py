"""Compare two fixed corpora with a new Java run; estimates are never counted as resolved."""
import argparse
import csv
import hashlib
import json
import random
from collections import Counter
from pathlib import Path
from summarize_date_validation import plain_text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('prior', type=Path)
    parser.add_argument('expanded', type=Path)
    parser.add_argument('revision', type=Path)
    args = parser.parse_args()
    predictions = {}
    input_hashes = {}
    labels = {}
    for folder in (args.prior, args.expanded):
        reviewed = json.loads((folder / 'source-review.json').read_text(encoding='utf-8'))
        for item in reviewed.get('items', reviewed.get('records', [])):
            labels[(int(item['appid']), item['gid'])] = item
    known_results = []
    short_estimates = []
    resolved_sources = []
    observed = set()
    for folder, name in ((args.prior, 'prior'), (args.expanded, 'expanded')):
        input_hashes[name] = hashlib.sha256((folder / 'input.tsv').read_bytes()).hexdigest()
        with (args.revision / f'{name}-output.tsv').open(encoding='utf-8') as source:
            rows = {(int(row['appid']), row['gid']): row for row in csv.DictReader(source, delimiter='\t')}
        run_keys = set()
        with (folder / 'corpus.jsonl').open(encoding='utf-8') as source:
            for line in source:
                item = json.loads(line)
                key = (item['appid'], str(item['gid']))
                assert key not in run_keys
                run_keys.add(key)
                row = rows[key]
                if key in observed:
                    assert row == predictions[key]
                    continue
                observed.add(key)
                predictions[key] = row
                if row['status'] == 'ESTIMATED':
                    assert not row['applied_at'] and row['source'] == 'PUBLICATION_DATE_PROXY'
                if key in labels:
                    known_results.append({'appid': key[0], 'gid': key[1], 'title': item['title'],
                        'review_label': labels[key]['label'], 'status': row['status'], 'date_reason': row['date_reason'],
                        'patch_date': row['patch_date'], 'applied_at': row['applied_at'],
                        'previous_expected_applied_at': labels[key].get('expected_applied_at'),
                        'previous_expected_patch_date': labels[key].get('expected_patch_date_kst')})
                if row['status'] not in ('RESOLVED', 'ESTIMATED'):
                    continue
                record = {**row, 'title': item['title'], 'game': item['game'], 'url': item.get('url'),
                    'raw_file': str(folder / item['raw_file']),
                    'body_sha256': hashlib.sha256(item.get('contents', '').encode()).hexdigest(),
                    'body': plain_text(item.get('contents', ''))}
                if row['status'] == 'RESOLVED':
                    resolved_sources.append(record)
                elif len(record['body']) <= 1800:
                    short_estimates.append(record)
        assert run_keys == rows.keys()
    summary = {'notice_pairs': len(predictions), 'apps': len({key[0] for key in predictions}),
        'status': dict(Counter(row['status'] for row in predictions.values())),
        'reasons': dict(Counter(row['date_reason'] for row in predictions.values())),
        'classifier': dict(Counter(row['decision'] for row in predictions.values())),
        'sources': dict(Counter(row['source'] for row in predictions.values())),
        'known_source_reviews_compared': len(known_results), 'input_sha256': input_hashes,
        'jar_sha256': hashlib.sha256((args.revision / 'thispatch-spark.jar').read_bytes()).hexdigest(),
        'estimated_short_body_population': len(short_estimates), 'estimated_sample_seed': 20260917,
        'warning': 'No population accuracy claim; known examples were used in development.'}
    sample = random.Random(20260917).sample(short_estimates, min(20, len(short_estimates)))
    for name, data in [('summary.json', summary), ('known-review-results.json', known_results),
                       ('resolved-sources.json', resolved_sources), ('estimated-review-20.json', sample)]:
        (args.revision / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print('Resolved:', [(item['appid'], item['gid'], item['patch_date'], item['applied_at']) for item in resolved_sources])


if __name__ == '__main__':
    main()
